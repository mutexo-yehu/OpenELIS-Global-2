package org.openelisglobal.bloodbank.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openelisglobal.bloodbank.dao.BloodUnitDAO;
import org.openelisglobal.bloodbank.dao.BloodUnitEventDAO;
import org.openelisglobal.bloodbank.dao.BloodUnitReservationDAO;
import org.openelisglobal.bloodbank.form.BloodBankForms.EmergencyIssueCheckRequest;
import org.openelisglobal.bloodbank.form.BloodBankForms.EmergencyIssueRequest;
import org.openelisglobal.bloodbank.form.BloodBankForms.RetrospectiveCheckRequest;
import org.openelisglobal.bloodbank.form.BloodBankViews.EmergencyIssueView;
import org.openelisglobal.bloodbank.form.BloodBankViews.EmergencyIssues;
import org.openelisglobal.bloodbank.form.BloodBankViews.IssueCheck;
import org.openelisglobal.bloodbank.form.BloodBankViews.UnitActionResult;
import org.openelisglobal.bloodbank.safety.BloodBankRules.RuleAction;
import org.openelisglobal.bloodbank.safety.EmergencyIssueChecker;
import org.openelisglobal.bloodbank.safety.IssueSafetyChecker.SecondCheckOutcome;
import org.openelisglobal.bloodbank.safety.SafetyCheck;
import org.openelisglobal.bloodbank.safety.SafetyCheck.CheckStatus;
import org.openelisglobal.bloodbank.safety.SecondCheckMode;
import org.openelisglobal.bloodbank.service.BloodBankException.Kind;
import org.openelisglobal.bloodbank.service.SecondPersonVerifier.Verification;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.Abo;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.ComponentClass;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.EventType;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.ReservationStatus;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.RetrospectiveResult;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.RhD;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.UnitStatus;
import org.openelisglobal.bloodbank.valueholder.BloodProductType;
import org.openelisglobal.bloodbank.valueholder.BloodUnit;
import org.openelisglobal.bloodbank.valueholder.BloodUnitEvent;
import org.openelisglobal.bloodbank.valueholder.BloodUnitReservation;
import org.openelisglobal.systemuser.service.SystemUserService;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The emergency issue and its retrospective check: every refusal writes
 * nothing, and the success paths record the EMERGENCY issue and the check.
 */
@RunWith(MockitoJUnitRunner.class)
public class BloodIssueServiceEmergencyTest {

    private static final Instant NOW = Instant.parse("2026-10-27T14:00:00Z");
    private static final String ISSUER = "10";
    private static final Long PATIENT = 501L;
    private static final Long UNIT = 1L;
    private static final Long RESERVATION = 80L;
    private static final String LAB_NUMBER = "LAB-0001";
    private static final String BAG_DIN = "G123498654321";

    @Mock
    private BloodUnitDAO unitDAO;
    @Mock
    private BloodUnitReservationDAO reservationDAO;
    @Mock
    private PatientBloodProfileService profileService;
    @Mock
    private BloodBankSettingsService settingsService;
    @Mock
    private SecondPersonVerifier secondPersonVerifier;
    @Mock
    private BloodUnitEventDAO eventDAO;
    @Mock
    private SystemUserService systemUserService;

    @InjectMocks
    private BloodIssueServiceImpl service;

    private BloodUnit unit;

    @Before
    public void setUp() {
        BloodUnitTrail trail = new BloodUnitTrail();
        ReflectionTestUtils.setField(trail, "eventDAO", eventDAO);
        ReflectionTestUtils.setField(trail, "systemUserService", systemUserService);
        ReflectionTestUtils.setField(service, "trail", trail);
        service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(settingsService.get()).thenReturn(BloodBankSettings.DEFAULTS);
        unit = unit(ComponentClass.RED_CELLS, Abo.O, RhD.NEG);
    }

    private static BloodUnit unit(ComponentClass component, Abo abo, RhD rhd) {
        BloodProductType type = new BloodProductType();
        type.setId(1L);
        type.setCode(component.name());
        type.setName(component.name());
        type.setComponentClass(component);
        type.setRequiresCrossmatch(component.carriesRedCells());
        BloodUnit unit = new BloodUnit();
        unit.setId(UNIT);
        unit.setDonationNumber(BAG_DIN);
        unit.setProductCode("E0291V00");
        unit.setProductType(type);
        unit.setAbo(abo);
        unit.setRhd(rhd);
        unit.setStatus(UnitStatus.AVAILABLE);
        unit.setExpiresAt(Timestamp.from(NOW.plus(Duration.ofDays(10))));
        unit.setReceivedAt(Timestamp.from(NOW.minus(Duration.ofDays(1))));
        unit.setReceivedBy(10);
        return unit;
    }

    private static BloodBankSettings settings(SecondCheckMode mode, boolean emergencyIssue) {
        return new BloodBankSettings("", "", "", "", "", List.of("Compatible"), 72, 48, 30, true, RuleAction.WARN,
                RuleAction.BLOCK, mode, emergencyIssue);
    }

    private static EmergencyIssueRequest request(Boolean acknowledged, String warningReason) {
        return new EmergencyIssueRequest(PATIENT, LAB_NUMBER, BAG_DIN, "Emergency department", "Porter Example",
                "Dr Example", "Massive haemorrhage after trauma", acknowledged, "checker", "secret-password", null,
                warningReason);
    }

    private static EmergencyIssueRequest withFields(Long patientId, String patientIdentifier, String location,
            String collector, String clinician, String reason) {
        return new EmergencyIssueRequest(patientId, patientIdentifier, BAG_DIN, location, collector, clinician, reason,
                null, "checker", "secret-password", null, null);
    }

    private void ready(Verification verification) {
        when(unitDAO.get(UNIT)).thenReturn(Optional.of(unit));
        when(unitDAO.getForUpdate(UNIT)).thenReturn(Optional.of(unit));
        when(profileService.resolvePatientIdentifier(LAB_NUMBER)).thenReturn(PATIENT);
        when(secondPersonVerifier.verify(ISSUER, "checker", "secret-password", null)).thenReturn(verification);
    }

    private static BloodBankException expectRefusal(Runnable call) {
        try {
            call.run();
        } catch (BloodBankException e) {
            return e;
        }
        fail("expected a refusal");
        return null;
    }

    private void nothingWritten() {
        Mockito.verify(reservationDAO, Mockito.never())
                .insert(ArgumentCaptor.forClass(BloodUnitReservation.class).capture());
        Mockito.verify(unitDAO, Mockito.never()).update(ArgumentCaptor.forClass(BloodUnit.class).capture());
        verifyZeroInteractions(eventDAO);
    }

    private static boolean failed(BloodBankException e, String code) {
        return e.getChecks().stream().anyMatch(c -> c.code().equals(code) && c.status() == CheckStatus.FAIL);
    }

    // ------------------------------------------------------------------ success

    @Test
    public void groupONegativeRedCellsGoOutMarkedEmergencyWithAPendingCheck() {
        ready(new Verification(SecondCheckOutcome.VERIFIED, 11));

        UnitActionResult result = service.emergencyIssue(UNIT, request(null, null), ISSUER);

        assertEquals(UnitStatus.ISSUED, result.unit().status());
        assertEquals(PATIENT, result.unit().patientId());
        ArgumentCaptor<BloodUnitReservation> reservation = ArgumentCaptor.forClass(BloodUnitReservation.class);
        Mockito.verify(reservationDAO).insert(reservation.capture());
        BloodUnitReservation saved = reservation.getValue();
        assertTrue(saved.isEmergency());
        assertEquals(ReservationStatus.ISSUED, saved.getStatus());
        assertEquals(PATIENT, saved.getPatientId());
        assertNull("no crossmatch", saved.getCrossmatchResultId());
        assertEquals("Dr Example", saved.getEmergencyClinician());
        assertEquals("Massive haemorrhage after trauma", saved.getEmergencyReason());
        assertFalse(saved.isEmergencyRhdPositiveAcknowledged());
        assertNull("the retrospective check is pending", saved.getRetrospectiveResult());
        assertEquals(Timestamp.from(NOW), saved.getIssuedAt());
        assertEquals(Integer.valueOf(10), saved.getReservedBy());

        ArgumentCaptor<BloodUnitEvent> event = ArgumentCaptor.forClass(BloodUnitEvent.class);
        Mockito.verify(eventDAO).append(event.capture());
        BloodUnitEvent entry = event.getValue();
        assertEquals(EventType.EMERGENCY_ISSUED, entry.getEventType());
        assertEquals(Integer.valueOf(10), entry.getPerformedBy());
        assertEquals(Integer.valueOf(11), entry.getSecondCheckerId());
        assertEquals("Emergency department", entry.getLocation());
        assertEquals("Porter Example", entry.getCollectorName());
        assertEquals(PATIENT, entry.getPatientId());
        assertTrue(entry.getDetails(), entry.getDetails().startsWith("EMERGENCY issue before group and crossmatch"));
        assertTrue(entry.getDetails(), entry.getDetails().contains("requested by: Dr Example"));
        assertTrue(entry.getDetails(),
                entry.getDetails().contains("clinical reason: Massive haemorrhage after trauma"));
        assertTrue(entry.getDetails(), entry.getDetails().contains("retrospective group and crossmatch pending"));
        assertTrue(entry.getDetails(), entry.getDetails().contains("EMERGENCY_PRODUCT=PASS"));
        assertTrue(entry.getDetails(), entry.getDetails().endsWith("; secondCheck: REQUIRED"));
        assertFalse(entry.getDetails(), entry.getDetails().contains("O RhD positive given"));
    }

    @Test
    public void groupORhDPositiveRedCellsGoOutWithTheAcknowledgementRecorded() {
        unit = unit(ComponentClass.RED_CELLS, Abo.O, RhD.POS);
        ready(new Verification(SecondCheckOutcome.VERIFIED, 11));

        service.emergencyIssue(UNIT, request(true, null), ISSUER);

        ArgumentCaptor<BloodUnitReservation> reservation = ArgumentCaptor.forClass(BloodUnitReservation.class);
        Mockito.verify(reservationDAO).insert(reservation.capture());
        assertTrue(reservation.getValue().isEmergencyRhdPositiveAcknowledged());
        ArgumentCaptor<BloodUnitEvent> event = ArgumentCaptor.forClass(BloodUnitEvent.class);
        Mockito.verify(eventDAO).append(event.capture());
        assertTrue(event.getValue().getDetails(), event.getValue().getDetails()
                .contains("O RhD positive given: " + EmergencyIssueChecker.RHD_POSITIVE_ACKNOWLEDGEMENT));
    }

    @Test
    public void groupABPlasmaGoesOutAndAnAcknowledgementSentWithItIsNotKept() {
        unit = unit(ComponentClass.PLASMA, Abo.AB, RhD.POS);
        ready(new Verification(SecondCheckOutcome.VERIFIED, 11));

        UnitActionResult result = service.emergencyIssue(UNIT, request(true, null), ISSUER);

        assertEquals(UnitStatus.ISSUED, result.unit().status());
        ArgumentCaptor<BloodUnitReservation> reservation = ArgumentCaptor.forClass(BloodUnitReservation.class);
        Mockito.verify(reservationDAO).insert(reservation.capture());
        assertFalse(reservation.getValue().isEmergencyRhdPositiveAcknowledged());
    }

    @Test
    public void underCollectorConfirmsNoSecondUserIsNeeded() {
        when(settingsService.get()).thenReturn(settings(SecondCheckMode.COLLECTOR_CONFIRMS, true));
        when(unitDAO.get(UNIT)).thenReturn(Optional.of(unit));
        when(unitDAO.getForUpdate(UNIT)).thenReturn(Optional.of(unit));
        when(profileService.resolvePatientIdentifier(LAB_NUMBER)).thenReturn(PATIENT);
        when(secondPersonVerifier.verify(ISSUER, null, null, null))
                .thenReturn(new Verification(SecondCheckOutcome.NOT_PROVIDED, null));

        UnitActionResult result = service.emergencyIssue(UNIT, new EmergencyIssueRequest(PATIENT, LAB_NUMBER, BAG_DIN,
                "Theatre 2", "Porter Example", "Dr Example", "Ruptured aneurysm", null, null, null, null, null), ISSUER);

        assertEquals(UnitStatus.ISSUED, result.unit().status());
    }

    @Test
    public void oneSignedInPersonUnderOptionalWithReasonNeedsAReason() {
        when(settingsService.get()).thenReturn(settings(SecondCheckMode.OPTIONAL_WITH_REASON, true));
        when(unitDAO.get(UNIT)).thenReturn(Optional.of(unit));
        when(unitDAO.getForUpdate(UNIT)).thenReturn(Optional.of(unit));
        when(profileService.resolvePatientIdentifier(LAB_NUMBER)).thenReturn(PATIENT);
        when(secondPersonVerifier.verify(ISSUER, null, null, null))
                .thenReturn(new Verification(SecondCheckOutcome.NOT_PROVIDED, null));
        EmergencyIssueRequest noReason = new EmergencyIssueRequest(PATIENT, LAB_NUMBER, BAG_DIN, "Theatre 2",
                "Porter Example", "Dr Example", "Ruptured aneurysm", null, null, null, null, null);

        BloodBankException e = expectRefusal(() -> service.emergencyIssue(UNIT, noReason, ISSUER));
        assertEquals("bloodbank.issue.error.warningReasonRequired", e.getErrorCode());
        nothingWritten();

        UnitActionResult result = service.emergencyIssue(UNIT,
                new EmergencyIssueRequest(PATIENT, LAB_NUMBER, BAG_DIN, "Theatre 2", "Porter Example", "Dr Example",
                        "Ruptured aneurysm", null, null, null, null, "Only one scientist on night shift"),
                ISSUER);
        assertEquals(UnitStatus.ISSUED, result.unit().status());
    }

    // ------------------------------------------------------------------ refusals

    @Test
    public void refusedWhenTheSiteHasTurnedEmergencyIssueOff() {
        when(settingsService.get()).thenReturn(settings(SecondCheckMode.REQUIRED, false));

        BloodBankException issue = expectRefusal(() -> service.emergencyIssue(UNIT, request(null, null), ISSUER));
        BloodBankException check = expectRefusal(() -> service.emergencyCheck(UNIT,
                new EmergencyIssueCheckRequest(PATIENT, LAB_NUMBER, BAG_DIN, null)));

        assertEquals(Kind.CONFLICT, issue.getKind());
        assertEquals("bloodbank.emergency.error.disabled", issue.getErrorCode());
        assertEquals("bloodbank.emergency.error.disabled", check.getErrorCode());
        verifyZeroInteractions(unitDAO, secondPersonVerifier, profileService);
        nothingWritten();
    }

    @Test
    public void everyRequiredFieldIsRequired() {
        String reason = "Massive haemorrhage";
        assertEquals("bloodbank.emergency.error.patientRequired", expectRefusal(() -> service.emergencyIssue(UNIT,
                withFields(null, LAB_NUMBER, "ED", "Porter", "Dr Example", reason), ISSUER)).getErrorCode());
        assertEquals("bloodbank.emergency.error.patientRequired",
                expectRefusal(() -> service.emergencyIssue(UNIT, null, ISSUER)).getErrorCode());
        assertEquals("bloodbank.issue.error.patientIdentifierRequired", expectRefusal(() -> service.emergencyIssue(UNIT,
                withFields(PATIENT, " ", "ED", "Porter", "Dr Example", reason), ISSUER)).getErrorCode());
        assertEquals("bloodbank.emergency.error.clinicianRequired", expectRefusal(() -> service.emergencyIssue(UNIT,
                withFields(PATIENT, LAB_NUMBER, "ED", "Porter", " ", reason), ISSUER)).getErrorCode());
        assertEquals("bloodbank.emergency.error.reasonRequired", expectRefusal(() -> service.emergencyIssue(UNIT,
                withFields(PATIENT, LAB_NUMBER, "ED", "Porter", "Dr Example", null), ISSUER)).getErrorCode());
        assertEquals("bloodbank.issue.error.locationRequired", expectRefusal(() -> service.emergencyIssue(UNIT,
                withFields(PATIENT, LAB_NUMBER, "", "Porter", "Dr Example", reason), ISSUER)).getErrorCode());
        assertEquals("bloodbank.issue.error.collectorRequired", expectRefusal(() -> service.emergencyIssue(UNIT,
                withFields(PATIENT, LAB_NUMBER, "ED", null, "Dr Example", reason), ISSUER)).getErrorCode());
        assertEquals("bloodbank.emergency.error.clinicianTooLong",
                expectRefusal(() -> service.emergencyIssue(UNIT,
                        withFields(PATIENT, LAB_NUMBER, "ED", "Porter", "D".repeat(121), reason), ISSUER))
                        .getErrorCode());
        assertEquals("bloodbank.error.reasonTooLong",
                expectRefusal(() -> service.emergencyIssue(UNIT,
                        withFields(PATIENT, LAB_NUMBER, "ED", "Porter", "Dr Example", "R".repeat(501)), ISSUER))
                        .getErrorCode());
        verifyZeroInteractions(unitDAO, secondPersonVerifier);
        nothingWritten();
    }

    @Test
    public void aUnitThatDoesNotExistIsNotFound() {
        when(unitDAO.get(UNIT)).thenReturn(Optional.empty());

        BloodBankException e = expectRefusal(() -> service.emergencyIssue(UNIT, request(null, null), ISSUER));

        assertEquals(Kind.NOT_FOUND, e.getKind());
        verifyZeroInteractions(secondPersonVerifier);
        nothingWritten();
    }

    @Test
    public void anyGroupOtherThanORedCellsOrABPlasmaIsRefused() {
        Object[][] refused = { { ComponentClass.RED_CELLS, Abo.A }, { ComponentClass.RED_CELLS, Abo.AB },
                { ComponentClass.PLASMA, Abo.O }, { ComponentClass.PLASMA, Abo.A }, { ComponentClass.PLATELETS, Abo.O },
                { ComponentClass.CRYO, Abo.AB }, { ComponentClass.WHOLE_BLOOD, Abo.O } };
        for (Object[] row : refused) {
            unit = unit((ComponentClass) row[0], (Abo) row[1], RhD.NEG);
            Mockito.reset(unitDAO, profileService, secondPersonVerifier);
            ready(new Verification(SecondCheckOutcome.VERIFIED, 11));

            BloodBankException e = expectRefusal(() -> service.emergencyIssue(UNIT, request(null, null), ISSUER));

            assertEquals(row[0] + " " + row[1], Kind.UNSAFE, e.getKind());
            assertEquals("bloodbank.emergency.error.checksFailed", e.getErrorCode());
            assertTrue(row[0] + " " + row[1], failed(e, EmergencyIssueChecker.PRODUCT));
        }
        nothingWritten();
    }

    @Test
    public void groupORhDPositiveRedCellsWithoutTheAcknowledgementAreRefused() {
        unit = unit(ComponentClass.RED_CELLS, Abo.O, RhD.POS);
        ready(new Verification(SecondCheckOutcome.VERIFIED, 11));

        BloodBankException absent = expectRefusal(() -> service.emergencyIssue(UNIT, request(null, null), ISSUER));
        BloodBankException declined = expectRefusal(() -> service.emergencyIssue(UNIT, request(false, null), ISSUER));

        assertTrue(failed(absent, EmergencyIssueChecker.RHD));
        assertTrue(failed(declined, EmergencyIssueChecker.RHD));
        nothingWritten();
    }

    @Test
    public void aUnitThatIsNotFreeInStockIsRefused() {
        Object[][] cases = { { UnitStatus.QUARANTINED, null }, { UnitStatus.ISSUED, 777L },
                { UnitStatus.DISCARDED, null }, { UnitStatus.TRANSFUSED, 777L }, { UnitStatus.RESERVED, 777L },
                { UnitStatus.RESERVED, PATIENT }, { UnitStatus.RETURNED_TO_SUPPLIER, null } };
        for (Object[] row : cases) {
            unit = unit(ComponentClass.RED_CELLS, Abo.O, RhD.NEG);
            unit.setStatus((UnitStatus) row[0]);
            unit.setPatientId((Long) row[1]);
            Mockito.reset(unitDAO, profileService, secondPersonVerifier);
            ready(new Verification(SecondCheckOutcome.VERIFIED, 11));

            BloodBankException e = expectRefusal(() -> service.emergencyIssue(UNIT, request(null, null), ISSUER));

            assertTrue(row[0] + " " + row[1], failed(e, "UNIT_STATUS"));
        }
        nothingWritten();
    }

    @Test
    public void anExpiredUnitIsRefused() {
        unit.setExpiresAt(Timestamp.from(NOW.minusSeconds(1)));
        ready(new Verification(SecondCheckOutcome.VERIFIED, 11));

        assertTrue(failed(expectRefusal(() -> service.emergencyIssue(UNIT, request(null, null), ISSUER)), "EXPIRY"));
        nothingWritten();
    }

    @Test
    public void anotherBagOrNoScanIsRefused() {
        ready(new Verification(SecondCheckOutcome.VERIFIED, 11));
        for (String scanned : new String[] { "G123498654322", null }) {
            EmergencyIssueRequest wrongBag = new EmergencyIssueRequest(PATIENT, LAB_NUMBER, scanned, "ED", "Porter",
                    "Dr Example", "Massive haemorrhage", null, "checker", "secret-password", null, null);

            assertTrue(String.valueOf(scanned),
                    failed(expectRefusal(() -> service.emergencyIssue(UNIT, wrongBag, ISSUER)), "UNIT_MATCH"));
        }
        nothingWritten();
    }

    @Test
    public void aScannedIdentifierOfAnotherPatientOrOfNobodyIsRefused() {
        when(unitDAO.get(UNIT)).thenReturn(Optional.of(unit));
        when(unitDAO.getForUpdate(UNIT)).thenReturn(Optional.of(unit));
        when(secondPersonVerifier.verify(ISSUER, "checker", "secret-password", null))
                .thenReturn(new Verification(SecondCheckOutcome.VERIFIED, 11));
        when(profileService.resolvePatientIdentifier("LAB-OTHER")).thenReturn(777L);
        when(profileService.resolvePatientIdentifier("LAB-NOBODY")).thenReturn(null);
        for (String scanned : new String[] { "LAB-OTHER", "LAB-NOBODY" }) {
            EmergencyIssueRequest other = new EmergencyIssueRequest(PATIENT, scanned, BAG_DIN, "ED", "Porter",
                    "Dr Example", "Massive haemorrhage", null, "checker", "secret-password", null, null);

            assertTrue(scanned,
                    failed(expectRefusal(() -> service.emergencyIssue(UNIT, other, ISSUER)), "PATIENT_MATCH"));
        }
        nothingWritten();
    }

    @Test
    public void everySecondCheckThatIsNotVerifiedIsRefused() {
        for (SecondCheckOutcome outcome : SecondCheckOutcome.values()) {
            if (outcome == SecondCheckOutcome.VERIFIED) {
                continue;
            }
            Mockito.reset(unitDAO, profileService, secondPersonVerifier);
            ready(new Verification(outcome, null));

            BloodBankException e = expectRefusal(() -> service.emergencyIssue(UNIT, request(null, null), ISSUER));

            assertEquals(outcome.name(), Kind.UNSAFE, e.getKind());
            assertTrue(outcome.name(), e.getChecks().stream()
                    .anyMatch(c -> c.code().equals("SECOND_PERSON") && c.status() != CheckStatus.PASS));
        }
        nothingWritten();
    }

    // ------------------------------------------------------------------ dry run

    @Test
    public void theDryRunShowsTheChecklistWithTheSecondPersonStillToSign() {
        when(unitDAO.get(UNIT)).thenReturn(Optional.of(unit));
        when(profileService.resolvePatientIdentifier(LAB_NUMBER)).thenReturn(PATIENT);

        IssueCheck check = service.emergencyCheck(UNIT, new EmergencyIssueCheckRequest(PATIENT, LAB_NUMBER, BAG_DIN, null));

        assertTrue(String.valueOf(check.checks()), check.allowed());
        assertEquals(PATIENT, check.scannedPatientId());
        SafetyCheck second = check.checks().stream().filter(c -> c.code().equals("SECOND_PERSON")).findFirst()
                .orElseThrow();
        assertEquals(CheckStatus.PENDING, second.status());
        assertEquals(SecondCheckMode.REQUIRED, check.secondCheckMode());
        verifyZeroInteractions(eventDAO, reservationDAO, secondPersonVerifier);
    }

    @Test
    public void theDryRunNeedsThePatient() {
        assertEquals("bloodbank.emergency.error.patientRequired",
                expectRefusal(() -> service.emergencyCheck(UNIT, null)).getErrorCode());
    }

    // ------------------------------------------------------------------ the list

    @Test
    public void thePatientsEmergencyIssuesAreListedWithTheSetting() {
        BloodUnitReservation issued = emergencyReservation();
        when(reservationDAO.getEmergencyByPatient(PATIENT)).thenReturn(List.of(issued));

        EmergencyIssues issues = service.emergencyIssues(PATIENT);

        assertTrue(issues.enabled());
        assertEquals(SecondCheckMode.REQUIRED, issues.secondCheckMode());
        assertEquals(1, issues.issues().size());
        EmergencyIssueView view = issues.issues().get(0);
        assertEquals(RESERVATION, view.reservationId());
        assertEquals(BAG_DIN, view.donationNumber());
        assertEquals("Dr Example", view.requestingClinician());
        assertEquals(NOW.toString(), view.issuedAt());
        assertNull(view.retrospectiveResult());
    }

    // ------------------------------------------------------------------
    // retrospective check

    private BloodUnitReservation emergencyReservation() {
        unit.setStatus(UnitStatus.ISSUED);
        unit.setPatientId(PATIENT);
        BloodUnitReservation reservation = new BloodUnitReservation();
        reservation.setId(RESERVATION);
        reservation.setUnit(unit);
        reservation.setPatientId(PATIENT);
        reservation.setStatus(ReservationStatus.ISSUED);
        reservation.setReservedBy(10);
        reservation.setReservedAt(Timestamp.from(NOW));
        reservation.setIssuedAt(Timestamp.from(NOW));
        reservation.setEmergency(true);
        reservation.setEmergencyClinician("Dr Example");
        reservation.setEmergencyReason("Massive haemorrhage");
        return reservation;
    }

    @Test
    public void aCompatibleRetrospectiveCheckIsRecordedOnceWithItsNote() {
        BloodUnitReservation reservation = emergencyReservation();
        when(reservationDAO.getForUpdate(RESERVATION)).thenReturn(Optional.of(reservation));

        EmergencyIssueView view = service.retrospectiveCheck(RESERVATION,
                new RetrospectiveCheckRequest("compatible", " Group O POS, crossmatch compatible on LAB-0002 "), "12");

        assertEquals(RetrospectiveResult.COMPATIBLE, view.retrospectiveResult());
        assertEquals("Group O POS, crossmatch compatible on LAB-0002", view.retrospectiveNote());
        assertEquals(Integer.valueOf(12), view.retrospectiveCheckedBy());
        assertEquals(NOW.toString(), view.retrospectiveCheckedAt());
        Mockito.verify(reservationDAO).update(reservation);
        ArgumentCaptor<BloodUnitEvent> event = ArgumentCaptor.forClass(BloodUnitEvent.class);
        Mockito.verify(eventDAO).append(event.capture());
        assertEquals(EventType.RETROSPECTIVE_CHECK, event.getValue().getEventType());
        assertEquals(BloodIssueServiceImpl.RETRO_COMPATIBLE, event.getValue().getDetails());
        assertEquals("Group O POS, crossmatch compatible on LAB-0002", event.getValue().getReason());
        assertEquals(PATIENT, event.getValue().getPatientId());

        BloodBankException again = expectRefusal(() -> service.retrospectiveCheck(RESERVATION,
                new RetrospectiveCheckRequest("INCOMPATIBLE", "second try"), "12"));
        assertEquals(Kind.CONFLICT, again.getKind());
        assertEquals("bloodbank.retro.error.alreadyRecorded", again.getErrorCode());
    }

    @Test
    public void anIncompatibleRetrospectiveCheckWarnsOfAPossibleReaction() {
        BloodUnitReservation reservation = emergencyReservation();
        when(reservationDAO.getForUpdate(RESERVATION)).thenReturn(Optional.of(reservation));

        EmergencyIssueView view = service.retrospectiveCheck(RESERVATION,
                new RetrospectiveCheckRequest("INCOMPATIBLE", "Anti-K found; unit K positive"), "12");

        assertEquals(RetrospectiveResult.INCOMPATIBLE, view.retrospectiveResult());
        ArgumentCaptor<BloodUnitEvent> event = ArgumentCaptor.forClass(BloodUnitEvent.class);
        Mockito.verify(eventDAO).append(event.capture());
        assertTrue(event.getValue().getDetails(), event.getValue().getDetails()
                .contains("possible transfusion reaction: inform the clinician and follow the reaction procedure"));
    }

    @Test
    public void aRetrospectiveCheckNeedsAnOutcomeAndANote() {
        assertEquals("bloodbank.retro.error.outcome",
                expectRefusal(() -> service.retrospectiveCheck(RESERVATION, null, "12")).getErrorCode());
        assertEquals("bloodbank.retro.error.outcome", expectRefusal(
                () -> service.retrospectiveCheck(RESERVATION, new RetrospectiveCheckRequest("MAYBE", "note"), "12"))
                .getErrorCode());
        assertEquals("bloodbank.retro.error.noteRequired", expectRefusal(
                () -> service.retrospectiveCheck(RESERVATION, new RetrospectiveCheckRequest("COMPATIBLE", "  "), "12"))
                .getErrorCode());
        assertEquals("bloodbank.error.reasonTooLong", expectRefusal(() -> service.retrospectiveCheck(RESERVATION,
                new RetrospectiveCheckRequest("COMPATIBLE", "n".repeat(501)), "12")).getErrorCode());
        verifyZeroInteractions(reservationDAO, eventDAO);
    }

    @Test
    public void aRetrospectiveCheckIsOnlyForAnEmergencyIssue() {
        BloodUnitReservation normal = emergencyReservation();
        normal.setEmergency(false);
        when(reservationDAO.getForUpdate(RESERVATION)).thenReturn(Optional.of(normal));
        when(reservationDAO.getForUpdate(99L)).thenReturn(Optional.empty());

        BloodBankException notEmergency = expectRefusal(() -> service.retrospectiveCheck(RESERVATION,
                new RetrospectiveCheckRequest("COMPATIBLE", "note"), "12"));
        BloodBankException missing = expectRefusal(
                () -> service.retrospectiveCheck(99L, new RetrospectiveCheckRequest("COMPATIBLE", "note"), "12"));

        assertEquals(Kind.CONFLICT, notEmergency.getKind());
        assertEquals("bloodbank.retro.error.notEmergency", notEmergency.getErrorCode());
        assertEquals(Kind.NOT_FOUND, missing.getKind());
        verifyZeroInteractions(eventDAO);
    }
}
