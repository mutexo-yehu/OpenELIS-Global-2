package org.openelisglobal.bloodbank.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import org.mockito.junit.MockitoJUnitRunner;
import org.openelisglobal.bloodbank.dao.BloodUnitDAO;
import org.openelisglobal.bloodbank.dao.BloodUnitEventDAO;
import org.openelisglobal.bloodbank.dao.BloodUnitReservationDAO;
import org.openelisglobal.bloodbank.form.BloodBankForms.IssueRequest;
import org.openelisglobal.bloodbank.form.BloodBankViews.CandidateUnit;
import org.openelisglobal.bloodbank.form.BloodBankViews.IssueCheck;
import org.openelisglobal.bloodbank.form.BloodBankViews.ReservationView;
import org.openelisglobal.bloodbank.form.BloodBankViews.UnitActionResult;
import org.openelisglobal.bloodbank.safety.BloodBankRules.RuleAction;
import org.openelisglobal.bloodbank.safety.IssueSafetyChecker.SecondCheckOutcome;
import org.openelisglobal.bloodbank.safety.PatientBloodProfile;
import org.openelisglobal.bloodbank.safety.PatientBloodProfile.CrossmatchResult;
import org.openelisglobal.bloodbank.safety.PatientBloodProfile.PatientGroup;
import org.openelisglobal.bloodbank.safety.SafetyCheck;
import org.openelisglobal.bloodbank.safety.SafetyCheck.CheckStatus;
import org.openelisglobal.bloodbank.safety.SecondCheckMode;
import org.openelisglobal.bloodbank.service.BloodBankException.Kind;
import org.openelisglobal.bloodbank.service.SecondPersonVerifier.Verification;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.Abo;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.ComponentClass;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.EventType;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.ReservationStatus;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.RhD;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.UnitStatus;
import org.openelisglobal.bloodbank.valueholder.BloodProductType;
import org.openelisglobal.bloodbank.valueholder.BloodUnit;
import org.openelisglobal.bloodbank.valueholder.BloodUnitEvent;
import org.openelisglobal.bloodbank.valueholder.BloodUnitReservation;
import org.openelisglobal.systemuser.service.SystemUserService;
import org.springframework.test.util.ReflectionTestUtils;

@RunWith(MockitoJUnitRunner.class)
public class BloodIssueServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-27T14:00:00Z");
    private static final String ISSUER = "10";
    private static final Long PATIENT = 501L;
    private static final Long UNIT = 1L;
    private static final Long CROSSMATCH = 9001L;
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
    private BloodUnitReservation reservation;

    @Before
    public void setUp() {
        BloodUnitTrail trail = new BloodUnitTrail();
        ReflectionTestUtils.setField(trail, "eventDAO", eventDAO);
        ReflectionTestUtils.setField(trail, "systemUserService", systemUserService);
        ReflectionTestUtils.setField(service, "trail", trail);
        service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(settingsService.get()).thenReturn(BloodBankSettings.DEFAULTS);
        unit = unit(ComponentClass.RED_CELLS, Abo.O, RhD.NEG, UnitStatus.AVAILABLE);
    }

    private static BloodUnit unit(ComponentClass component, Abo abo, RhD rhd, UnitStatus status) {
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
        unit.setStatus(status);
        unit.setExpiresAt(Timestamp.from(NOW.plus(Duration.ofDays(10))));
        unit.setReceivedAt(Timestamp.from(NOW.minus(Duration.ofDays(1))));
        unit.setReceivedBy(10);
        return unit;
    }

    private static PatientBloodProfile profile(Abo abo, RhD rhd, CrossmatchResult... crossmatches) {
        return new PatientBloodProfile(PATIENT, new PatientGroup(abo, rhd, true, false, 2, 2), List.of(), null, null,
                List.of(crossmatches), List.of());
    }

    private static CrossmatchResult crossmatch(Instant validUntil, boolean compatible, Long linkedUnit) {
        return new CrossmatchResult(CROSSMATCH, 30L, LAB_NUMBER, validUntil.minus(Duration.ofHours(72)), validUntil,
                compatible ? "Compatible" : "Incompatible", compatible, linkedUnit,
                linkedUnit == null ? null : "W000026000001");
    }

    private void reservedFor(Long patientId) {
        unit.setStatus(UnitStatus.RESERVED);
        unit.setPatientId(patientId);
        reservation = new BloodUnitReservation();
        reservation.setId(70L);
        reservation.setUnit(unit);
        reservation.setPatientId(patientId);
        reservation.setCrossmatchResultId(CROSSMATCH);
        reservation.setStatus(ReservationStatus.ACTIVE);
        reservation.setReservedAt(Timestamp.from(NOW.minus(Duration.ofHours(1))));
        reservation.setReservedBy(10);
        lenient().when(reservationDAO.getForUnit(UNIT, ReservationStatus.ACTIVE)).thenReturn(Optional.of(reservation));
    }

    private void issuedFor(Long patientId, Instant issuedAt) {
        reservedFor(patientId);
        unit.setStatus(UnitStatus.ISSUED);
        reservation.setStatus(ReservationStatus.ISSUED);
        reservation.setIssuedAt(Timestamp.from(issuedAt));
        when(unitDAO.getForUpdate(UNIT)).thenReturn(Optional.of(unit));
        when(reservationDAO.getForUnit(UNIT, ReservationStatus.ISSUED)).thenReturn(Optional.of(reservation));
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

    // ------------------------------------------------------------------ reserve

    @Test
    public void reserveHoldsTheUnitAndLinksTheCrossmatch() {
        when(unitDAO.getForUpdate(UNIT)).thenReturn(Optional.of(unit));
        when(profileService.getProfile(PATIENT))
                .thenReturn(profile(Abo.A, RhD.NEG, crossmatch(NOW.plus(Duration.ofHours(5)), true, null)));

        ReservationView view = service.reserve(UNIT, PATIENT, CROSSMATCH, ISSUER);

        assertEquals(ReservationStatus.ACTIVE, view.status());
        assertEquals(NOW.plus(Duration.ofHours(5)).toString(), view.crossmatchValidUntil());
        verify(reservationDAO).insert(argThat(r -> PATIENT.equals(r.getPatientId())
                && CROSSMATCH.equals(r.getCrossmatchResultId()) && r.getStatus() == ReservationStatus.ACTIVE
                && Integer.valueOf(10).equals(r.getReservedBy())));
        verify(unitDAO).update(argThat(u -> u.getStatus() == UnitStatus.RESERVED && PATIENT.equals(u.getPatientId())));
        verify(eventDAO).append(argThat(e -> e.getEventType() == EventType.RESERVED && PATIENT.equals(e.getPatientId())
                && e.getDetails().contains(LAB_NUMBER)));
    }

    @Test
    public void reserveRefusesAUnitThatIsNotAvailable() {
        unit.setStatus(UnitStatus.QUARANTINED);
        when(unitDAO.getForUpdate(UNIT)).thenReturn(Optional.of(unit));

        BloodBankException e = expectRefusal(() -> service.reserve(UNIT, PATIENT, CROSSMATCH, ISSUER));

        assertEquals(Kind.CONFLICT, e.getKind());
        assertEquals("bloodbank.reserve.error.notAvailable", e.getErrorCode());
        verifyZeroInteractions(reservationDAO, eventDAO, profileService);
    }

    @Test
    public void reserveRefusesAnIncompatibleUnitWithTheReason() {
        unit.setAbo(Abo.B);
        when(unitDAO.getForUpdate(UNIT)).thenReturn(Optional.of(unit));
        when(profileService.getProfile(PATIENT))
                .thenReturn(profile(Abo.A, RhD.NEG, crossmatch(NOW.plus(Duration.ofHours(5)), true, null)));

        BloodBankException e = expectRefusal(() -> service.reserve(UNIT, PATIENT, CROSSMATCH, ISSUER));

        assertEquals(Kind.UNSAFE, e.getKind());
        SafetyCheck abo = e.getChecks().stream().filter(c -> "ABO".equals(c.code())).findFirst().orElseThrow();
        assertEquals(CheckStatus.FAIL, abo.status());
        verifyZeroInteractions(reservationDAO, eventDAO);
    }

    @Test
    public void redCellsCannotBeReservedWithoutACrossmatch() {
        when(unitDAO.getForUpdate(UNIT)).thenReturn(Optional.of(unit));
        when(profileService.getProfile(PATIENT)).thenReturn(profile(Abo.A, RhD.NEG));

        BloodBankException e = expectRefusal(() -> service.reserve(UNIT, PATIENT, null, ISSUER));

        assertEquals("bloodbank.reserve.error.crossmatchRequired", e.getErrorCode());
        verifyZeroInteractions(reservationDAO);
    }

    @Test
    public void aCrossmatchExpiringNowCannotCoverAUnit() {
        when(unitDAO.getForUpdate(UNIT)).thenReturn(Optional.of(unit));
        when(profileService.getProfile(PATIENT)).thenReturn(profile(Abo.A, RhD.NEG, crossmatch(NOW, true, null)));

        BloodBankException e = expectRefusal(() -> service.reserve(UNIT, PATIENT, CROSSMATCH, ISSUER));

        assertEquals("bloodbank.reserve.error.crossmatchExpired", e.getErrorCode());
        verifyZeroInteractions(reservationDAO);
    }

    @Test
    public void aCrossmatchCoversOneUnitOnly() {
        when(unitDAO.getForUpdate(UNIT)).thenReturn(Optional.of(unit));
        when(profileService.getProfile(PATIENT))
                .thenReturn(profile(Abo.A, RhD.NEG, crossmatch(NOW.plus(Duration.ofHours(5)), true, 2L)));

        BloodBankException e = expectRefusal(() -> service.reserve(UNIT, PATIENT, CROSSMATCH, ISSUER));

        assertEquals(Kind.CONFLICT, e.getKind());
        assertEquals(List.of("W000026000001"), e.getParams());
    }

    @Test
    public void anIncompatibleOrUnknownCrossmatchIsRefused() {
        when(unitDAO.getForUpdate(UNIT)).thenReturn(Optional.of(unit));
        when(profileService.getProfile(PATIENT))
                .thenReturn(profile(Abo.A, RhD.NEG, crossmatch(NOW.plus(Duration.ofHours(5)), false, null)));

        assertEquals("bloodbank.reserve.error.crossmatchIncompatible",
                expectRefusal(() -> service.reserve(UNIT, PATIENT, CROSSMATCH, ISSUER)).getErrorCode());
        assertEquals("bloodbank.reserve.error.crossmatchNotFound",
                expectRefusal(() -> service.reserve(UNIT, PATIENT, 1234L, ISSUER)).getErrorCode());
    }

    @Test
    public void plateletsAreReservedWithoutACrossmatch() {
        unit = unit(ComponentClass.PLATELETS, Abo.A, RhD.POS, UnitStatus.AVAILABLE);
        when(unitDAO.getForUpdate(UNIT)).thenReturn(Optional.of(unit));
        when(profileService.getProfile(PATIENT)).thenReturn(profile(Abo.A, RhD.POS));

        ReservationView view = service.reserve(UNIT, PATIENT, CROSSMATCH, ISSUER);

        assertEquals(null, view.crossmatchResultId());
    }

    @Test
    public void reserveNeedsAUnitAndAPatient() {
        assertEquals(Kind.INVALID, expectRefusal(() -> service.reserve(null, PATIENT, null, ISSUER)).getKind());
        assertEquals(Kind.INVALID, expectRefusal(() -> service.reserve(UNIT, null, null, ISSUER)).getKind());
        assertEquals("bloodbank.error.noUser",
                expectRefusal(() -> service.reserve(UNIT, PATIENT, null, null)).getErrorCode());
    }

    @Test
    public void candidatesCarryTheVerdictPerUnit() {
        BloodUnit compatible = unit(ComponentClass.RED_CELLS, Abo.O, RhD.NEG, UnitStatus.AVAILABLE);
        BloodUnit incompatible = unit(ComponentClass.RED_CELLS, Abo.B, RhD.NEG, UnitStatus.AVAILABLE);
        incompatible.setId(2L);
        BloodUnit expired = unit(ComponentClass.RED_CELLS, Abo.A, RhD.NEG, UnitStatus.AVAILABLE);
        expired.setId(3L);
        expired.setExpiresAt(Timestamp.from(NOW));
        when(profileService.getProfile(PATIENT)).thenReturn(profile(Abo.A, RhD.NEG));
        when(unitDAO.search(List.of(UnitStatus.AVAILABLE), 1L, null, null, null))
                .thenReturn(List.of(compatible, incompatible, expired));

        List<CandidateUnit> candidates = service.candidates(PATIENT, 1L);

        assertEquals(List.of(true, false, false), candidates.stream().map(CandidateUnit::selectable).toList());
        assertTrue(candidates.get(2).checks().stream()
                .anyMatch(c -> "EXPIRY".equals(c.code()) && c.status() == CheckStatus.FAIL));
    }

    // ------------------------------------------------------------------ issue

    private IssueRequest request(String warningReason) {
        return new IssueRequest(LAB_NUMBER, "Ward 3", "Porter Example", "checker", "secret-password", null,
                warningReason, BAG_DIN);
    }

    private void readyToIssue(Verification verification) {
        reservedFor(PATIENT);
        when(unitDAO.get(UNIT)).thenReturn(Optional.of(unit));
        when(unitDAO.getForUpdate(UNIT)).thenReturn(Optional.of(unit));
        when(profileService.resolvePatientIdentifier(LAB_NUMBER)).thenReturn(PATIENT);
        when(profileService.getProfile(PATIENT))
                .thenReturn(profile(Abo.A, RhD.NEG, crossmatch(NOW.plus(Duration.ofHours(5)), true, UNIT)));
        when(secondPersonVerifier.verify(ISSUER, "checker", "secret-password", null)).thenReturn(verification);
    }

    @Test
    public void issueRecordsTheSecondCheckerWardAndCollector() {
        readyToIssue(new Verification(SecondCheckOutcome.VERIFIED, 11));

        UnitActionResult result = service.issue(UNIT, request(null), ISSUER);

        assertEquals(UnitStatus.ISSUED, result.unit().status());
        verify(reservationDAO).update(
                argThat(r -> r.getStatus() == ReservationStatus.ISSUED && Timestamp.from(NOW).equals(r.getIssuedAt())));
        ArgumentCaptor<BloodUnitEvent> event = ArgumentCaptor.forClass(BloodUnitEvent.class);
        verify(eventDAO).append(event.capture());
        assertEquals(EventType.ISSUED, event.getValue().getEventType());
        assertEquals(Integer.valueOf(11), event.getValue().getSecondCheckerId());
        assertEquals(Integer.valueOf(10), event.getValue().getPerformedBy());
        assertEquals("Ward 3", event.getValue().getLocation());
        assertEquals("Porter Example", event.getValue().getCollectorName());
        assertEquals(PATIENT, event.getValue().getPatientId());
        assertTrue(event.getValue().getDetails().contains("SECOND_PERSON=PASS"));
        assertTrue(event.getValue().getDetails().endsWith("; secondCheck: REQUIRED"));
    }

    @Test
    public void issueIsRefusedForEverySecondCheckThatIsNotVerified() {
        for (SecondCheckOutcome outcome : SecondCheckOutcome.values()) {
            if (outcome == SecondCheckOutcome.VERIFIED) {
                continue;
            }
            readyToIssue(new Verification(outcome, null));
            BloodBankException e = expectRefusal(() -> service.issue(UNIT, request(null), ISSUER));
            assertEquals(outcome.name(), Kind.UNSAFE, e.getKind());
            assertTrue(outcome.name(), e.getChecks().stream()
                    .anyMatch(c -> "SECOND_PERSON".equals(c.code()) && c.status() != CheckStatus.PASS));
        }
        verify(reservationDAO, never()).update(reservation);
        verifyZeroInteractions(eventDAO);
    }

    @Test
    public void issueIsRefusedWhenTheScannedPatientIsSomeoneElse() {
        readyToIssue(new Verification(SecondCheckOutcome.VERIFIED, 11));
        when(profileService.resolvePatientIdentifier("LAB-OTHER")).thenReturn(777L);

        BloodBankException e = expectRefusal(() -> service.issue(UNIT,
                new IssueRequest("LAB-OTHER", "Ward 3", "Porter", "checker", "secret-password", null, null, BAG_DIN),
                ISSUER));

        assertEquals("bloodbank.issue.error.checksFailed", e.getErrorCode());
        assertTrue(e.getChecks().stream()
                .anyMatch(c -> "PATIENT_MATCH".equals(c.code()) && c.status() == CheckStatus.FAIL));
        verifyZeroInteractions(eventDAO);
    }

    @Test
    public void issueIsRefusedWhenTheBagInHandIsAnotherUnit() {
        readyToIssue(new Verification(SecondCheckOutcome.VERIFIED, 11));

        BloodBankException e = expectRefusal(() -> service.issue(UNIT, new IssueRequest(LAB_NUMBER, "Ward 3",
                "Porter Example", "checker", "secret-password", null, null, "=G12349865432200"), ISSUER));

        assertEquals(Kind.UNSAFE, e.getKind());
        assertEquals("bloodbank.issue.error.checksFailed", e.getErrorCode());
        assertTrue(e.getChecks().stream().anyMatch(c -> "UNIT_MATCH".equals(c.code()) && c.status() == CheckStatus.FAIL
                && "bloodbank.check.unitMatch.mismatch".equals(c.messageKey())));
        verify(reservationDAO, never()).update(reservation);
        verify(unitDAO, never()).update(unit);
        verifyZeroInteractions(eventDAO);
    }

    @Test
    public void issueIsRefusedWhenNoBagWasScanned() {
        readyToIssue(new Verification(SecondCheckOutcome.VERIFIED, 11));

        BloodBankException e = expectRefusal(() -> service.issue(UNIT, new IssueRequest(LAB_NUMBER, "Ward 3",
                "Porter Example", "checker", "secret-password", null, null, null), ISSUER));

        assertTrue(e.getChecks().stream().anyMatch(
                c -> "UNIT_MATCH".equals(c.code()) && "bloodbank.check.unitMatch.notScanned".equals(c.messageKey())));
        verifyZeroInteractions(eventDAO);
    }

    @Test
    public void theDryRunShowsAnotherBagAsFailed() {
        reservedFor(PATIENT);
        when(unitDAO.get(UNIT)).thenReturn(Optional.of(unit));
        when(profileService.resolvePatientIdentifier(LAB_NUMBER)).thenReturn(PATIENT);
        when(profileService.getProfile(PATIENT))
                .thenReturn(profile(Abo.A, RhD.NEG, crossmatch(NOW.plus(Duration.ofHours(5)), true, UNIT)));

        IssueCheck check = service.check(UNIT, LAB_NUMBER, "G123498654322");

        assertFalse(check.allowed());
        assertEquals(CheckStatus.FAIL,
                check.checks().stream().filter(c -> "UNIT_MATCH".equals(c.code())).findFirst().get().status());
        verifyZeroInteractions(secondPersonVerifier, eventDAO);
    }

    @Test
    public void aWarningNeedsAReasonWhichGoesInTheTrail() {
        unit = unit(ComponentClass.PLATELETS, Abo.A, RhD.POS, UnitStatus.AVAILABLE);
        reservedFor(PATIENT);
        reservation.setCrossmatchResultId(null);
        when(unitDAO.get(UNIT)).thenReturn(Optional.of(unit));
        when(unitDAO.getForUpdate(UNIT)).thenReturn(Optional.of(unit));
        when(profileService.resolvePatientIdentifier(LAB_NUMBER)).thenReturn(PATIENT);
        when(profileService.getProfile(PATIENT)).thenReturn(profile(Abo.A, RhD.NEG));
        when(secondPersonVerifier.verify(ISSUER, "checker", "secret-password", null))
                .thenReturn(new Verification(SecondCheckOutcome.VERIFIED, 11));

        BloodBankException e = expectRefusal(() -> service.issue(UNIT, request("  "), ISSUER));
        assertEquals("bloodbank.issue.error.warningReasonRequired", e.getErrorCode());
        verifyZeroInteractions(eventDAO);

        service.issue(UNIT, request("No RhD-negative platelets in stock; consultant agreed"), ISSUER);
        verify(eventDAO).append(argThat(ev -> ev.getEventType() == EventType.ISSUED
                && "No RhD-negative platelets in stock; consultant agreed".equals(ev.getReason())));
    }

    @Test
    public void wardCollectorAndPatientAreRequiredBeforeAnyoneIsAsked() {
        assertEquals("bloodbank.issue.error.locationRequired",
                expectRefusal(() -> service.issue(UNIT,
                        new IssueRequest(LAB_NUMBER, " ", "Porter", "checker", "pw", null, null, BAG_DIN), ISSUER))
                        .getErrorCode());
        assertEquals("bloodbank.issue.error.collectorRequired",
                expectRefusal(() -> service.issue(UNIT,
                        new IssueRequest(LAB_NUMBER, "Ward", null, "checker", "pw", null, null, BAG_DIN), ISSUER))
                        .getErrorCode());
        assertEquals("bloodbank.issue.error.patientIdentifierRequired",
                expectRefusal(() -> service.issue(UNIT, null, ISSUER)).getErrorCode());
        verifyZeroInteractions(secondPersonVerifier, unitDAO, eventDAO);
    }

    @Test
    public void theDryRunShowsTheSecondPersonAsPending() {
        reservedFor(PATIENT);
        when(unitDAO.get(UNIT)).thenReturn(Optional.of(unit));
        when(profileService.resolvePatientIdentifier(LAB_NUMBER)).thenReturn(PATIENT);
        when(profileService.getProfile(PATIENT))
                .thenReturn(profile(Abo.A, RhD.NEG, crossmatch(NOW.plus(Duration.ofHours(5)), true, UNIT)));

        IssueCheck check = service.check(UNIT, LAB_NUMBER, BAG_DIN);

        assertTrue(check.allowed());
        assertEquals(PATIENT, check.scannedPatientId());
        assertEquals(CheckStatus.PENDING, check.checks().get(check.checks().size() - 1).status());
        verifyZeroInteractions(secondPersonVerifier, eventDAO);
    }

    // ------------------------------------------------------------------ return,
    // transfused

    @Test
    public void aReturnExactlyAtTheEndOfTheWindowGoesBackToStock() {
        issuedFor(PATIENT, NOW.minus(Duration.ofMinutes(30)));

        UnitActionResult result = service.returnUnit(UNIT, null, ISSUER);

        assertEquals(UnitStatus.AVAILABLE, result.unit().status());
        assertEquals(null, result.unit().patientId());
        verify(eventDAO, times(1)).append(argThat(e -> e.getEventType() == EventType.RETURNED));
        verify(reservationDAO).update(argThat(r -> r.getStatus() == ReservationStatus.RELEASED));
    }

    @Test
    public void aReturnAfterTheWindowIsDiscarded() {
        issuedFor(PATIENT, NOW.minus(Duration.ofMinutes(31)));

        UnitActionResult result = service.returnUnit(UNIT, "came back warm", ISSUER);

        assertEquals(UnitStatus.DISCARDED, result.unit().status());
        assertEquals(List.of(EventType.RETURNED, EventType.DISCARDED),
                result.events().stream().map(e -> e.eventType()).toList());
    }

    @Test
    public void onlyAnIssuedUnitCanComeBackOrBeTransfused() {
        when(unitDAO.getForUpdate(UNIT)).thenReturn(Optional.of(unit));

        assertEquals(Kind.CONFLICT, expectRefusal(() -> service.returnUnit(UNIT, null, ISSUER)).getKind());
        assertEquals(Kind.CONFLICT, expectRefusal(() -> service.transfused(UNIT, null, ISSUER)).getKind());
        verifyZeroInteractions(eventDAO);
    }

    @Test
    public void aReactionNoteIsItsOwnTrailEntry() {
        issuedFor(PATIENT, NOW.minus(Duration.ofMinutes(90)));

        UnitActionResult result = service.transfused(UNIT, "Fever 38.6 at 20 minutes; stopped", ISSUER);

        assertEquals(UnitStatus.TRANSFUSED, result.unit().status());
        assertEquals(List.of(EventType.TRANSFUSED, EventType.REACTION),
                result.events().stream().map(e -> e.eventType()).toList());
        verify(eventDAO).append(argThat(e -> e.getEventType() == EventType.REACTION
                && "Fever 38.6 at 20 minutes; stopped".equals(e.getReason())));
    }

    @Test
    public void transfusedWithoutANoteWritesOneEntry() {
        issuedFor(PATIENT, NOW.minus(Duration.ofMinutes(90)));

        UnitActionResult result = service.transfused(UNIT, " ", ISSUER);

        assertEquals(1, result.events().size());
    }

    @Test
    public void unreserveNeedsAReasonAndAnActiveReservation() {
        assertEquals("bloodbank.error.reasonRequired",
                expectRefusal(() -> service.unreserve(70L, "", ISSUER)).getErrorCode());
        reservedFor(PATIENT);
        reservation.setStatus(ReservationStatus.ISSUED);
        when(reservationDAO.get(70L)).thenReturn(Optional.of(reservation));
        assertEquals(Kind.CONFLICT,
                expectRefusal(() -> service.unreserve(70L, "patient discharged", ISSUER)).getKind());
        verifyZeroInteractions(eventDAO);
    }

    @Test
    public void unreservePutsTheUnitBack() {
        reservedFor(PATIENT);
        when(reservationDAO.get(70L)).thenReturn(Optional.of(reservation));
        when(unitDAO.getForUpdate(UNIT)).thenReturn(Optional.of(unit));

        ReservationView view = service.unreserve(70L, "patient discharged", ISSUER);

        assertEquals(ReservationStatus.RELEASED, view.status());
        verify(unitDAO).update(argThat(u -> u.getStatus() == UnitStatus.AVAILABLE && u.getPatientId() == null));
        verify(eventDAO).append(argThat(e -> e.getEventType() == EventType.UNRESERVED
                && "patient discharged".equals(e.getReason()) && PATIENT.equals(e.getPatientId())));
    }

    @Test
    public void theUnconfirmedGroupRuleDecidesGroupORedCells() {
        PatientBloodProfile oneSample = new PatientBloodProfile(PATIENT,
                new PatientGroup(Abo.A, RhD.NEG, false, false, 1, 1), List.of(), null, null,
                List.of(crossmatch(NOW.plus(Duration.ofHours(5)), true, UNIT)), List.of());
        reservedFor(PATIENT);
        when(unitDAO.get(UNIT)).thenReturn(Optional.of(unit));
        when(profileService.resolvePatientIdentifier(LAB_NUMBER)).thenReturn(PATIENT);
        when(profileService.getProfile(PATIENT)).thenReturn(oneSample);

        assertEquals(false, service.check(UNIT, LAB_NUMBER, BAG_DIN).allowed());

        when(settingsService.get()).thenReturn(new BloodBankSettings("", "", "", "", "", List.of("Compatible"), 72, 48,
                30, true, RuleAction.WARN, RuleAction.WARN, SecondCheckMode.REQUIRED));
        IssueCheck warned = service.check(UNIT, LAB_NUMBER, BAG_DIN);
        assertTrue(warned.allowed());
        assertTrue(warned.warningReasonRequired());
        verify(profileService, times(2)).getProfile(eq(PATIENT));
    }

    // ------------------------------------------------------------------ second
    // check setting

    private void secondCheck(SecondCheckMode mode) {
        BloodBankSettings d = BloodBankSettings.DEFAULTS;
        when(settingsService.get()).thenReturn(new BloodBankSettings("", "", "", "", "", d.crossmatchCompatibleValues(),
                72, 48, 30, true, d.rhdNegPatientRhdPosPlatelets(), d.unconfirmedGroupORedCells(), mode));
    }

    private static IssueRequest alone(String reason) {
        return new IssueRequest(LAB_NUMBER, "Ward 3", "Porter Example", null, null, null, reason, BAG_DIN);
    }

    private void readyToIssueAlone() {
        readyToIssue(new Verification(SecondCheckOutcome.VERIFIED, 11));
        when(secondPersonVerifier.verify(ISSUER, null, null, null))
                .thenReturn(new Verification(SecondCheckOutcome.NOT_PROVIDED, null));
    }

    @Test
    public void requiredSecondCheckRefusesOnePerson() {
        secondCheck(SecondCheckMode.REQUIRED);
        readyToIssueAlone();

        BloodBankException e = expectRefusal(() -> service.issue(UNIT, alone("nobody else on shift"), ISSUER));

        assertEquals("bloodbank.issue.error.checksFailed", e.getErrorCode());
        assertTrue(e.getChecks().stream()
                .anyMatch(c -> "SECOND_PERSON".equals(c.code()) && c.status() == CheckStatus.PENDING));
        verifyZeroInteractions(eventDAO);
    }

    @Test
    public void optionalSecondCheckNeedsAReasonForOnePersonAndKeepsIt() {
        secondCheck(SecondCheckMode.OPTIONAL_WITH_REASON);
        readyToIssueAlone();

        BloodBankException e = expectRefusal(() -> service.issue(UNIT, alone(" "), ISSUER));
        assertEquals("bloodbank.issue.error.warningReasonRequired", e.getErrorCode());
        verifyZeroInteractions(eventDAO);

        service.issue(UNIT, alone("Night shift, one scientist on duty"), ISSUER);

        ArgumentCaptor<BloodUnitEvent> event = ArgumentCaptor.forClass(BloodUnitEvent.class);
        verify(eventDAO).append(event.capture());
        assertEquals("Night shift, one scientist on duty", event.getValue().getReason());
        assertEquals(null, event.getValue().getSecondCheckerId());
        assertTrue(event.getValue().getDetails(), event.getValue().getDetails().contains("SECOND_PERSON=WARN")
                && event.getValue().getDetails().endsWith("; secondCheck: OPTIONAL_WITH_REASON"));
    }

    @Test
    public void optionalSecondCheckWithASecondPersonNeedsNoReason() {
        secondCheck(SecondCheckMode.OPTIONAL_WITH_REASON);
        readyToIssue(new Verification(SecondCheckOutcome.VERIFIED, 11));

        service.issue(UNIT, request(null), ISSUER);

        verify(eventDAO).append(argThat(ev -> Integer.valueOf(11).equals(ev.getSecondCheckerId())
                && ev.getReason() == null && ev.getDetails().contains("SECOND_PERSON=PASS")));
    }

    @Test
    public void collectorConfirmsIssuesWithoutASecondSignIn() {
        secondCheck(SecondCheckMode.COLLECTOR_CONFIRMS);
        readyToIssueAlone();

        UnitActionResult result = service.issue(UNIT, alone(null), ISSUER);

        assertEquals(UnitStatus.ISSUED, result.unit().status());
        verify(eventDAO).append(argThat(ev -> "Porter Example".equals(ev.getCollectorName())
                && ev.getSecondCheckerId() == null && ev.getDetails().contains("SECOND_PERSON=PASS")
                && ev.getDetails().endsWith("; secondCheck: COLLECTOR_CONFIRMS")));
    }

    @Test
    public void theDryRunTellsTheScreenWhichSecondCheckApplies() {
        for (SecondCheckMode mode : SecondCheckMode.values()) {
            secondCheck(mode);
            reservedFor(PATIENT);
            when(unitDAO.get(UNIT)).thenReturn(Optional.of(unit));
            when(profileService.resolvePatientIdentifier(LAB_NUMBER)).thenReturn(PATIENT);
            when(profileService.getProfile(PATIENT))
                    .thenReturn(profile(Abo.A, RhD.NEG, crossmatch(NOW.plus(Duration.ofHours(5)), true, UNIT)));

            IssueCheck check = service.check(UNIT, LAB_NUMBER, BAG_DIN);

            assertEquals(mode, check.secondCheckMode());
            assertTrue(mode.name(), check.allowed());
            assertEquals(mode.name(), mode == SecondCheckMode.OPTIONAL_WITH_REASON, check.warningReasonRequired());
        }
        verifyZeroInteractions(secondPersonVerifier, eventDAO);
    }
}
