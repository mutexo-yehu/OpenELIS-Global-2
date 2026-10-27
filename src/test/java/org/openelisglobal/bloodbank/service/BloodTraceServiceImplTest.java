package org.openelisglobal.bloodbank.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.openelisglobal.bloodbank.dao.BloodProfileResultDAO;
import org.openelisglobal.bloodbank.dao.BloodUnitDAO;
import org.openelisglobal.bloodbank.dao.BloodUnitEventDAO;
import org.openelisglobal.bloodbank.dao.BloodUnitReservationDAO;
import org.openelisglobal.bloodbank.form.BloodBankViews.EventView;
import org.openelisglobal.bloodbank.service.BloodBankException.Kind;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.EventType;
import org.openelisglobal.bloodbank.valueholder.BloodUnit;
import org.openelisglobal.bloodbank.valueholder.BloodUnitEvent;
import org.openelisglobal.bloodbank.valueholder.BloodUnitReservation;
import org.openelisglobal.patient.service.PatientService;
import org.openelisglobal.patient.valueholder.Patient;
import org.openelisglobal.systemuser.service.SystemUserService;
import org.openelisglobal.systemuser.valueholder.SystemUser;
import org.springframework.test.util.ReflectionTestUtils;

@RunWith(MockitoJUnitRunner.class)
public class BloodTraceServiceImplTest {

    @Mock
    private BloodUnitDAO unitDAO;
    @Mock
    private BloodUnitEventDAO eventDAO;
    @Mock
    private PatientService patientService;
    @Mock
    private SystemUserService systemUserService;
    @Mock
    private BloodUnitReservationDAO reservationDAO;
    @Mock
    private BloodProfileResultDAO profileResultDAO;

    @InjectMocks
    private BloodTraceServiceImpl service;

    @Before
    public void setUp() {
        BloodUnitTrail trail = new BloodUnitTrail();
        ReflectionTestUtils.setField(trail, "eventDAO", eventDAO);
        ReflectionTestUtils.setField(trail, "systemUserService", systemUserService);
        ReflectionTestUtils.setField(service, "trail", trail);
    }

    @Test
    public void csvQuotesAndDefusesFormulas() {
        EventView event = new EventView(1L, 2L, "G123498654321", "E0291V00", EventType.ISSUED, 501L, 70L, 10,
                "Smith, Jo", 11, "Lee \"Sam\"", "2026-10-27T14:00:00Z", "=HYPERLINK(\"x\")", "Ward 3", "Porter",
                "line one\nline two");

        String csv = service.toCsv(List.of(event));
        String[] lines = csv.substring(BloodTraceServiceImpl.BOM.length()).split("\r\n", -1);

        assertEquals("occurredAtUtc,eventType,donationNumber,productCode,unitId,patientId,patientName,"
                + "patientNationalId,labNumber,reservationId,performedBy,secondChecker,location,collectorName,reason,"
                + "details", lines[0]);
        assertEquals("2026-10-27T14:00:00Z,ISSUED,G123498654321,E0291V00,2,501,,,,70,\"Smith, Jo\",\"Lee \"\"Sam\"\"\","
                + "Ward 3,Porter,\"'=HYPERLINK(\"\"x\"\")\",\"line one\nline two\"", lines[1]);
        String named = service.toCsv(List.of(event.withPatient("Jo Example", "NID-1", "26-000123")));
        assertTrue(named, named.split("\r\n")[1].contains(",501,Jo Example,NID-1,26-000123,70,"));
    }

    @Test
    public void csvStartsWithAByteOrderMarkSoSpreadsheetsReadAccents() {
        EventView event = new EventView(1L, 2L, "G123498654321", "E0291V00", EventType.RECEIVED, null, null, 10,
                "Ren\u00e9e Example", null, null, "2026-10-27T14:00:00Z", null, null, null, null);

        String csv = service.toCsv(List.of(event));

        assertTrue(csv.startsWith("\uFEFFoccurredAtUtc,"));
        assertTrue(csv, csv.contains(",Ren\u00e9e Example,"));
        assertEquals(1, csv.chars().filter(c -> c == '\uFEFF').count());
    }

    @Test
    public void csvDetailsGiveTimesInLocalTimeWithTheOffsetAndOccurredAtStaysUtc() {
        service.setZone(ZoneOffset.ofHours(-4));
        EventView event = new EventView(1L, 2L, "G123498654321", "E0291V00", EventType.RESERVED, 501L, 70L, 10,
                "Jo Example", null, null, "2026-10-27T14:00:00Z", null, null, null,
                "crossmatch result 900 (26-000123), valid until 2026-10-30T03:55:00Z");

        String row = service.toCsv(List.of(event)).split("\r\n")[1];

        assertTrue(row, row.startsWith("2026-10-27T14:00:00Z,RESERVED,"));
        assertTrue(row, row.endsWith(",\"crossmatch result 900 (26-000123), valid until 2026-10-29 23:55 UTC-04:00\""));
        assertEquals("no time here", BloodTraceServiceImpl.localTimes("no time here", ZoneOffset.UTC));
        assertEquals("at 2026-10-30 03:55 UTC+00:00",
                BloodTraceServiceImpl.localTimes("at 2026-10-30T03:55Z", ZoneOffset.UTC));
        assertNull(BloodTraceServiceImpl.localTimes(null, ZoneOffset.UTC));
    }

    @Test
    public void emptyCellsStayEmpty() {
        assertEquals("", BloodTraceServiceImpl.cell(null));
        assertEquals("'-5", BloodTraceServiceImpl.cell("-5"));
        assertEquals("plain", BloodTraceServiceImpl.cell("plain"));
    }

    @Test
    public void aDonationNumberIsCheckedBeforeSearching() {
        try {
            service.forDonationNumber("not-a-din");
            fail("expected a refusal");
        } catch (BloodBankException e) {
            assertEquals(Kind.INVALID, e.getKind());
        }
        verifyZeroInteractions(unitDAO, eventDAO);
    }

    @Test
    public void aDonationNumberFindsEveryComponent() {
        BloodUnit red = new BloodUnit();
        red.setId(2L);
        BloodUnit plasma = new BloodUnit();
        plasma.setId(3L);
        when(unitDAO.getByDonationNumber("G123498654321")).thenReturn(List.of(red, plasma));
        when(eventDAO.getByUnits(List.of(2L, 3L))).thenReturn(List.of());

        assertEquals(List.of(), service.forDonationNumber("G123 498 654 321 H"));
    }

    @Test
    public void anUnknownPatientIsNotFound() {
        when(patientService.getData("999")).thenReturn(null);
        try {
            service.forPatient(999L);
            fail("expected not found");
        } catch (BloodBankException e) {
            assertEquals(Kind.NOT_FOUND, e.getKind());
        }
        verifyZeroInteractions(eventDAO);
    }

    private static BloodUnitEvent event(long id, EventType type, Long patientId, Long reservationId) {
        BloodUnit unit = new BloodUnit();
        unit.setId(2L);
        unit.setDonationNumber("G123498654321");
        unit.setProductCode("E0291V00");
        BloodUnitEvent event = new BloodUnitEvent();
        event.setId(id);
        event.setUnit(unit);
        event.setEventType(type);
        event.setPatientId(patientId);
        event.setReservationId(reservationId);
        event.setPerformedBy(10);
        event.setOccurredAt(Timestamp.from(Instant.parse("2026-10-27T14:00:00Z")));
        return event;
    }

    @Test
    public void theTrailNamesThePatientTheLabNumberAndThePeople() {
        Patient patient = new Patient();
        when(patientService.getData("501")).thenReturn(patient);
        when(patientService.getFirstName(patient)).thenReturn("Jo");
        when(patientService.getLastName(patient)).thenReturn("Example");
        when(patientService.getNationalId(patient)).thenReturn("NID-1");
        BloodUnitReservation reservation = new BloodUnitReservation();
        reservation.setCrossmatchResultId(9001L);
        when(reservationDAO.get(70L)).thenReturn(Optional.of(reservation));
        when(profileResultDAO.getAccessionForResult("9001")).thenReturn("26-000123");
        SystemUser issuer = new SystemUser();
        issuer.setFirstName("Sam");
        issuer.setLastName("Lee");
        when(systemUserService.getUserById("10")).thenReturn(issuer);
        when(eventDAO.getByPatient(501L)).thenReturn(List.of(event(1L, EventType.RESERVED, 501L, 70L),
                event(2L, EventType.ISSUED, 501L, 70L), event(3L, EventType.RETURNED, 501L, null)));

        List<EventView> trail = service.forPatient(501L);

        assertEquals("Jo Example", trail.get(0).patientName());
        assertEquals("NID-1", trail.get(0).patientNationalId());
        assertEquals("26-000123", trail.get(1).labNumber());
        assertNull(trail.get(2).labNumber());
        assertEquals("Sam Lee", trail.get(0).performedByName());
        // one look-up per patient (plus the existence check) and per reservation, not
        // per entry
        verify(patientService, times(2)).getData("501");
        verify(reservationDAO, times(1)).get(70L);
    }

    @Test
    public void aUserWithoutANameShowsTheirLogin() {
        SystemUser user = new SystemUser();
        user.setLoginName("checker");
        assertEquals("checker", BloodUnitTrail.firstLast(user));
        user.setFirstName(" Ann ");
        assertEquals("Ann", BloodUnitTrail.firstLast(user));
    }
}
