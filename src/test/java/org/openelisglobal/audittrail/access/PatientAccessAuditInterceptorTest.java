package org.openelisglobal.audittrail.access;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.openelisglobal.common.action.IActionConstants;
import org.openelisglobal.login.valueholder.UserSessionData;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

public class PatientAccessAuditInterceptorTest {

    private final PatientAccessLogWriter writer = mock(PatientAccessLogWriter.class);
    private final PatientAccessAuditInterceptor interceptor = new PatientAccessAuditInterceptor(writer, true, "");

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/OpenELIS-Global" + path);
        request.setContextPath("/OpenELIS-Global");
        UserSessionData usd = new UserSessionData();
        usd.setSytemUserId(7);
        usd.setLoginName("sci1");
        request.getSession().setAttribute(IActionConstants.USER_SESSION_DATA, usd);
        return request;
    }

    private PatientAccessRecord recorded(MockHttpServletRequest request, int status) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(status);
        interceptor.afterCompletion(request, response, null, null);
        ArgumentCaptor<PatientAccessRecord> captor = ArgumentCaptor.forClass(PatientAccessRecord.class);
        verify(writer).submit(captor.capture());
        return captor.getValue();
    }

    @Test
    public void patientDetailsRead_isRecordedWithUserAndPatient() {
        MockHttpServletRequest request = request("GET", "/rest/patient-details");
        request.setParameter("patientID", "123");
        request.setQueryString("patientID=123");
        request.addHeader("X-Forwarded-For", "6.6.6.6, 10.0.0.5");

        PatientAccessRecord r = recorded(request, 200);

        assertEquals(Integer.valueOf(7), r.sysUserId());
        assertEquals("sci1", r.loginName());
        assertEquals("GET", r.httpMethod());
        assertEquals("/rest/patient-details", r.resource());
        assertEquals("patientID=123", r.queryString());
        assertEquals(Integer.valueOf(123), r.patientId());
        assertNull(r.accessionNumber());
        assertEquals("10.0.0.5", r.clientAddress()); // the proxy's entry, not the client-supplied one
        assertEquals(200, r.httpStatus());
    }

    @Test
    public void resultsByLabNumber_recordsAccession() {
        MockHttpServletRequest request = request("GET", "/rest/LogbookResults");
        request.setParameter("labNumber", " DEV0126000001 ");

        PatientAccessRecord r = recorded(request, 200);

        assertNull(r.patientId());
        assertEquals("DEV0126000001", r.accessionNumber());
    }

    @Test
    public void pathVariable_isUsedWhenNoParameter() {
        MockHttpServletRequest request = request("GET", "/rest/patient-photos/55");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("patientId", "55"));

        assertEquals(Integer.valueOf(55), recorded(request, 200).patientId());
    }

    @Test
    public void patientPhoto_idPathVariable_isThePatient() {
        MockHttpServletRequest request = request("GET", "/rest/patient-photos/6/true");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("id", "6", "isThumbnail", "true"));

        assertEquals(Integer.valueOf(6), recorded(request, 200).patientId());
    }

    @Test
    public void idPathVariable_isIgnoredOutsidePatientEndpoints() {
        MockHttpServletRequest request = request("GET", "/rest/LogbookResults");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("id", "6"));

        assertNull(recorded(request, 200).patientId());
    }

    @Test
    public void searchAsYouType_burstIsRecordedOnceWithTheLastSearch() {
        for (String term : List.of("T", "Te", "Tes", "Test")) {
            for (String field : List.of("lastName", "firstName")) {
                MockHttpServletRequest request = request("GET", "/rest/patient-search");
                request.setQueryString(field + "=" + term);
                interceptor.afterCompletion(request, new MockHttpServletResponse(), null, null);
            }
        }
        interceptor.flushSearches(PatientAccessAuditInterceptor.SEARCH_QUIET_MILLIS);
        verify(writer, never()).submit(any()); // still typing

        interceptor.flushSearches(0);
        ArgumentCaptor<PatientAccessRecord> captor = ArgumentCaptor.forClass(PatientAccessRecord.class);
        verify(writer).submit(captor.capture());
        assertEquals("firstName=Test", captor.getValue().queryString());

        interceptor.flushSearches(0);
        verify(writer).submit(any()); // and only once
    }

    @Test
    public void nonNumericPatientId_isKeptOnlyInQuery() {
        MockHttpServletRequest request = request("GET", "/rest/patient-details");
        request.setParameter("patientID", "1 OR 1=1");
        request.setQueryString("patientID=1%20OR%201=1");

        PatientAccessRecord r = recorded(request, 200);
        assertNull(r.patientId());
        assertEquals("patientID=1%20OR%201=1", r.queryString());
    }

    @Test
    public void reportPrintPost_isRecorded() {
        recorded(request("POST", "/rest/ReportPrint"), 200);
    }

    @Test
    public void otherPosts_failedRequests_andExceptions_areNotRecorded() {
        interceptor.afterCompletion(request("POST", "/rest/SampleEdit"), new MockHttpServletResponse(), null, null);

        MockHttpServletResponse forbidden = new MockHttpServletResponse();
        forbidden.setStatus(403);
        interceptor.afterCompletion(request("GET", "/rest/patient-details"), forbidden, null, null);

        interceptor.afterCompletion(request("GET", "/rest/patient-details"), new MockHttpServletResponse(), null,
                new RuntimeException("boom"));

        verify(writer, never()).submit(any());
    }

    @Test
    public void pathsSetting_overridesDefaults() {
        assertEquals(PatientAccessAuditInterceptor.DEFAULT_PATHS, PatientAccessAuditInterceptor.parsePaths(" "));
        assertEquals(List.of("/rest/a", "/rest/b/**"),
                PatientAccessAuditInterceptor.parsePaths(" /rest/a, /rest/b/** ,"));
    }

    @Test
    public void readsAreGets() {
        assertTrue(PatientAccessAuditInterceptor.isRead(new MockHttpServletRequest("GET", "/x")));
        assertFalse(PatientAccessAuditInterceptor.isRead(new MockHttpServletRequest("DELETE", "/x")));
    }

    @Test
    public void query_filtersByPatientIncludingTheirLabNumbers() {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = PatientAccessLogRestController.where("2026-10-01", "2026-10-08", "7", "123", "50%_off", params);

        assertTrue(where.contains("l.patient_id = :patientId OR l.accession_number IN"));
        assertEquals(123, params.getValue("patientId"));
        assertEquals(7, params.getValue("userId"));
        assertEquals("%50\\%\\_off%", params.getValue("search"));
        assertEquals(java.sql.Timestamp.valueOf("2026-10-09 00:00:00"), params.getValue("end"));
    }

    @Test
    public void query_ignoresMalformedFilters() {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = PatientAccessLogRestController.where("yesterday", null, "x", "1;drop", " ", params);

        assertEquals(" WHERE 1=1", where);
        assertEquals(0, params.getParameterNames().length);
    }

    @Test
    public void csv_quotesAndNeutralisesFormulas() {
        assertEquals("\"a\"\"b\"", PatientAccessLogRestController.csv("a\"b"));
        assertEquals("\"'=HYPERLINK(1)\"", PatientAccessLogRestController.csv("=HYPERLINK(1)"));
        assertEquals("", PatientAccessLogRestController.csv(null));
    }
}
