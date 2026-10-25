package org.openelisglobal.bloodbank.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.openelisglobal.bloodbank.dao.BloodUnitDAO;
import org.openelisglobal.bloodbank.dao.BloodUnitEventDAO;
import org.openelisglobal.bloodbank.form.BloodBankViews.EventView;
import org.openelisglobal.bloodbank.service.BloodBankException.Kind;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.EventType;
import org.openelisglobal.bloodbank.valueholder.BloodUnit;
import org.openelisglobal.patient.service.PatientService;
import org.openelisglobal.systemuser.service.SystemUserService;
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
        String[] lines = csv.split("\r\n", -1);

        assertEquals("occurredAt,eventType,donationNumber,productCode,unitId,patientId,reservationId,performedBy,"
                + "secondChecker,location,collectorName,reason,details", lines[0]);
        assertEquals("2026-10-27T14:00:00Z,ISSUED,G123498654321,E0291V00,2,501,70,\"Smith, Jo\",\"Lee \"\"Sam\"\"\","
                + "Ward 3,Porter,\"'=HYPERLINK(\"\"x\"\")\",\"line one\nline two\"", lines[1]);
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
}
