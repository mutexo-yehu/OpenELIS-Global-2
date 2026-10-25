package org.openelisglobal.bloodbank.controller.rest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.openelisglobal.bloodbank.form.BloodBankForms.IssueRequest;
import org.openelisglobal.bloodbank.safety.SafetyCheck;
import org.openelisglobal.bloodbank.service.BloodBankException;
import org.openelisglobal.bloodbank.service.BloodBankException.Kind;
import org.openelisglobal.bloodbank.service.BloodBankSettingsService;
import org.openelisglobal.bloodbank.service.BloodIssueService;
import org.openelisglobal.bloodbank.service.BloodProductCatalogService;
import org.openelisglobal.bloodbank.service.BloodTraceService;
import org.openelisglobal.bloodbank.service.BloodUnitService;
import org.openelisglobal.bloodbank.service.PatientBloodProfileService;
import org.openelisglobal.common.action.IActionConstants;
import org.openelisglobal.login.valueholder.UserSessionData;
import org.openelisglobal.security.SecuritySliceMockMvcTest;
import org.openelisglobal.testsupport.SliceSecurityConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Every blood bank endpoint: refused without a sign-in (401) and to a role
 * outside its tier (403), both before any service is called; admitted for the
 * roles of its tier.
 *
 * <p>
 * BaseWebContextSensitiveTest cannot pin this: its context leaves
 * {@code @PreAuthorize} inert.
 */
@WebAppConfiguration
@ContextConfiguration(classes = { SliceSecurityConfig.class, BloodBankRestSecurityTest.TestConfig.class })
public class BloodBankRestSecurityTest extends SecuritySliceMockMvcTest {

    private record Endpoint(HttpMethod method, String path) {
        MockHttpServletRequestBuilder build() {
            MockHttpServletRequestBuilder builder = request(method, path).session(sessionForSysUser(10));
            if (method != HttpMethod.GET) {
                builder.contentType(MediaType.APPLICATION_JSON).content("{}");
            }
            return builder;
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    private static Endpoint get(String path) {
        return new Endpoint(HttpMethod.GET, path);
    }

    private static Endpoint post(String path) {
        return new Endpoint(HttpMethod.POST, path);
    }

    private static Endpoint put(String path) {
        return new Endpoint(HttpMethod.PUT, path);
    }

    /** READ and BENCH: RESULTS, VALIDATION and ADMIN. */
    private static final List<Endpoint> BENCH_AND_READ = List.of(get("/rest/bloodbank/units"),
            get("/rest/bloodbank/units?status=AVAILABLE,RESERVED&abo=O&rhd=NEG&expiringWithinHours=48"),
            get("/rest/bloodbank/units/summary"), get("/rest/bloodbank/units/5"), get("/rest/bloodbank/units/5/trace"),
            get("/rest/bloodbank/patients/501/profile"), get("/rest/bloodbank/patients/501/candidate-units"),
            get("/rest/bloodbank/patients/501/trace"), get("/rest/bloodbank/trace?unit=5"),
            get("/rest/bloodbank/trace/export?patient=501"), get("/rest/bloodbank/product-types"),
            post("/rest/bloodbank/units/parse"), post("/rest/bloodbank/units/receive"),
            post("/rest/bloodbank/units/5/quarantine"), post("/rest/bloodbank/units/5/discard"),
            post("/rest/bloodbank/units/5/return-to-supplier"), post("/rest/bloodbank/units/5/issue/check"),
            post("/rest/bloodbank/units/5/issue"), post("/rest/bloodbank/units/5/return"),
            post("/rest/bloodbank/units/5/transfused"), post("/rest/bloodbank/reservations"),
            post("/rest/bloodbank/reservations/70/release"));

    /** SUPERVISOR: VALIDATION and ADMIN. */
    private static final List<Endpoint> SUPERVISOR = List.of(post("/rest/bloodbank/units/5/release"));

    /** ADMIN only. */
    private static final List<Endpoint> ADMIN = List.of(get("/rest/bloodbank/settings"),
            put("/rest/bloodbank/settings"), post("/rest/bloodbank/product-types"),
            put("/rest/bloodbank/product-types/1"), get("/rest/bloodbank/product-codes"),
            post("/rest/bloodbank/product-codes"), put("/rest/bloodbank/product-codes/1"));

    @Autowired
    private BloodUnitService unitService;
    @Autowired
    private BloodIssueService issueService;
    @Autowired
    private BloodTraceService traceService;
    @Autowired
    private PatientBloodProfileService profileService;
    @Autowired
    private BloodBankSettingsService settingsService;
    @Autowired
    private BloodProductCatalogService catalogService;

    private Object[] services() {
        return new Object[] { unitService, issueService, traceService, profileService, settingsService,
                catalogService };
    }

    @Before
    public void resetMocks() {
        reset(services());
    }

    private List<Endpoint> all() {
        return java.util.stream.Stream.of(BENCH_AND_READ, SUPERVISOR, ADMIN).flatMap(List::stream).toList();
    }

    @Test
    public void everyEndpointRefusesAnUnauthenticatedCallerBeforeAnyService() throws Exception {
        for (Endpoint endpoint : all()) {
            int status = mockMvc.perform(endpoint.build()).andReturn().getResponse().getStatus();
            assertEquals(endpoint.toString(), 401, status);
        }
        verifyZeroInteractions(services());
    }

    @Test
    public void everyEndpointRefusesReceptionBeforeAnyService() throws Exception {
        for (Endpoint endpoint : all()) {
            assertEquals(endpoint.toString(), 403, status(endpoint, user("reception").roles("RECEPTION")));
        }
        verifyZeroInteractions(services());
    }

    @Test
    public void benchAndReadEndpointsAdmitResultsValidationAndAdmin() throws Exception {
        for (String role : List.of("RESULTS", "VALIDATION", "ADMIN")) {
            for (Endpoint endpoint : BENCH_AND_READ) {
                reset(services());
                int status = status(endpoint, user("u").roles(role));
                assertTrue(role + " " + endpoint + " -> " + status, status >= 200 && status < 300);
                assertTrue(role + " " + endpoint + " reached a service", serviceWasCalled());
            }
        }
    }

    @Test
    public void releaseFromQuarantineIsForSupervisors() throws Exception {
        for (Endpoint endpoint : SUPERVISOR) {
            assertEquals(endpoint.toString(), 403, status(endpoint, user("results").roles("RESULTS")));
            verifyZeroInteractions(services());
            assertEquals(endpoint.toString(), 200, status(endpoint, user("validation").roles("VALIDATION")));
            assertTrue(serviceWasCalled());
        }
    }

    @Test
    public void configurationIsForAdministrators() throws Exception {
        for (Endpoint endpoint : ADMIN) {
            assertEquals(endpoint.toString(), 403, status(endpoint, user("results").roles("RESULTS")));
            assertEquals(endpoint.toString(), 403, status(endpoint, user("validation").roles("VALIDATION")));
            verifyZeroInteractions(services());
            int status = status(endpoint, user("admin").roles("ADMIN"));
            assertTrue(endpoint + " -> " + status, status >= 200 && status < 300);
            assertTrue(serviceWasCalled());
            reset(services());
        }
    }

    @Test
    public void refusalsMapToTheirStatusWithTheChecklist() throws Exception {
        IssueRequest empty = new IssueRequest(null, null, null, null, null, null, null);
        when(issueService.issue(5L, empty, "10"))
                .thenThrow(new BloodBankException(Kind.UNSAFE, "bloodbank.issue.error.checksFailed",
                        List.of(SafetyCheck.fail("ABO", "bloodbank.check.abo.incompatible", "A", "O"))));
        MvcResult unsafe = mockMvc
                .perform(post("/rest/bloodbank/units/5/issue").build().with(user("results").roles("RESULTS")))
                .andReturn();
        assertEquals(422, unsafe.getResponse().getStatus());
        String body = unsafe.getResponse().getContentAsString();
        assertTrue(body, body.contains("\"errorCode\":\"bloodbank.issue.error.checksFailed\""));
        assertTrue(body, body.contains("\"code\":\"ABO\"") && body.contains("\"status\":\"FAIL\""));

        when(unitService.get(6L))
                .thenThrow(new BloodBankException(Kind.NOT_FOUND, "bloodbank.error.unitNotFound", "6"));
        assertEquals(404, status(get("/rest/bloodbank/units/6"), user("results").roles("RESULTS")));
        when(unitService.get(7L)).thenThrow(new BloodBankException(Kind.CONFLICT, "bloodbank.error.transition"));
        assertEquals(409, status(get("/rest/bloodbank/units/7"), user("results").roles("RESULTS")));
        // an unknown group in a filter is the caller's mistake
        assertEquals(400, status(get("/rest/bloodbank/units?abo=Q"), user("results").roles("RESULTS")));
        // the trace needs exactly one criterion
        assertEquals(400, status(get("/rest/bloodbank/trace?unit=5&patient=501"), user("results").roles("RESULTS")));
        verifyZeroInteractions(traceService);
    }

    private int status(Endpoint endpoint, RequestPostProcessor principal) throws Exception {
        MvcResult result = mockMvc.perform(endpoint.build().with(principal)).andReturn();
        return result.getResponse().getStatus();
    }

    private boolean serviceWasCalled() {
        for (Object service : services()) {
            if (!mockingDetails(service).getInvocations().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * ControllerUtills.getSysUserId falls back to a SpringContext bean this slice
     * has no context for, so hand it the session attribute the login flow sets.
     */
    private static MockHttpSession sessionForSysUser(int sysUserId) {
        UserSessionData sessionData = new UserSessionData();
        sessionData.setSytemUserId(sysUserId);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(IActionConstants.USER_SESSION_DATA, sessionData);
        return session;
    }

    @Configuration
    static class TestConfig {

        @Bean
        BloodUnitService bloodUnitService() {
            return mock(BloodUnitService.class);
        }

        @Bean
        BloodIssueService bloodIssueService() {
            return mock(BloodIssueService.class);
        }

        @Bean
        BloodTraceService bloodTraceService() {
            return mock(BloodTraceService.class);
        }

        @Bean
        PatientBloodProfileService patientBloodProfileService() {
            return mock(PatientBloodProfileService.class);
        }

        @Bean
        BloodBankSettingsService bloodBankSettingsService() {
            return mock(BloodBankSettingsService.class);
        }

        @Bean
        BloodProductCatalogService bloodProductCatalogService() {
            return mock(BloodProductCatalogService.class);
        }

        @Bean
        BloodUnitRestController bloodUnitRestController() {
            return new BloodUnitRestController();
        }

        @Bean
        BloodBankPatientRestController bloodBankPatientRestController() {
            return new BloodBankPatientRestController();
        }

        @Bean
        BloodBankReservationRestController bloodBankReservationRestController() {
            return new BloodBankReservationRestController();
        }

        @Bean
        BloodBankTraceRestController bloodBankTraceRestController() {
            return new BloodBankTraceRestController();
        }

        @Bean
        BloodBankAdminRestController bloodBankAdminRestController() {
            return new BloodBankAdminRestController();
        }
    }
}
