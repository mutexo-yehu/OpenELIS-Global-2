package org.openelisglobal.bloodbank.safety;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.Test;
import org.openelisglobal.bloodbank.isbt.Isbt128Parser;
import org.openelisglobal.bloodbank.safety.BloodBankRules.RuleAction;
import org.openelisglobal.bloodbank.safety.IssueSafetyChecker.IssueSnapshot;
import org.openelisglobal.bloodbank.safety.IssueSafetyChecker.ReservationFacts;
import org.openelisglobal.bloodbank.safety.IssueSafetyChecker.SecondCheckOutcome;
import org.openelisglobal.bloodbank.safety.IssueSafetyChecker.UnitFacts;
import org.openelisglobal.bloodbank.safety.PatientBloodProfile.CrossmatchResult;
import org.openelisglobal.bloodbank.safety.PatientBloodProfile.PatientGroup;
import org.openelisglobal.bloodbank.safety.SafetyCheck.CheckStatus;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.Abo;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.ComponentClass;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.ReservationStatus;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.RhD;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.UnitStatus;

public class IssueSafetyCheckerTest {

    private static final Instant NOW = Instant.parse("2026-10-27T14:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final long PATIENT = 501L;
    private static final long OTHER_PATIENT = 502L;
    private static final long CROSSMATCH = 9001L;
    private static final String DIN = "G123498654321";

    private static UnitFacts redCells(UnitStatus status, Instant expiresAt, Long patientId) {
        return new UnitFacts(1L, status, ComponentClass.RED_CELLS, true, Abo.O, RhD.NEG, expiresAt, patientId, DIN);
    }

    private static ReservationFacts reservation(Long patientId, Long crossmatchId) {
        return new ReservationFacts(7L, patientId, ReservationStatus.ACTIVE, crossmatchId);
    }

    private static PatientBloodProfile profile(CrossmatchResult... crossmatches) {
        return new PatientBloodProfile(PATIENT, new PatientGroup(Abo.A, RhD.NEG, true, false, 2, 2), List.of(), null,
                null, List.of(crossmatches), List.of());
    }

    private static CrossmatchResult crossmatch(boolean compatible, Instant validUntil) {
        return new CrossmatchResult(CROSSMATCH, 30L, "LAB-30", validUntil.minus(Duration.ofHours(72)), validUntil,
                compatible ? "Compatible" : "Incompatible", compatible, null, null);
    }

    /**
     * Everything right: a reserved, crossmatched, in-date unit for the scanned
     * patient, second person verified.
     */
    private static IssueSnapshot good() {
        return new IssueSnapshot(redCells(UnitStatus.RESERVED, NOW.plus(Duration.ofDays(10)), PATIENT),
                reservation(PATIENT, CROSSMATCH), PATIENT, profile(crossmatch(true, NOW.plus(Duration.ofHours(5)))),
                BloodBankRules.DEFAULTS, SecondCheckOutcome.VERIFIED, DIN);
    }

    private static Map<String, SafetyCheck> byCode(List<SafetyCheck> checks) {
        return checks.stream().collect(Collectors.toMap(SafetyCheck::code, Function.identity()));
    }

    @Test
    public void theGoodCasePassesEveryCheck() {
        List<SafetyCheck> checks = IssueSafetyChecker.check(good(), CLOCK);

        assertEquals(List.of("UNIT_STATUS", "UNIT_MATCH", "EXPIRY", "PATIENT_MATCH", "GROUP_CONFIRMED", "ABO", "RHD",
                "CROSSMATCH", "SECOND_PERSON"), checks.stream().map(SafetyCheck::code).toList());
        assertTrue(checks.stream().allMatch(c -> c.status() == CheckStatus.PASS));
        assertTrue(IssueSafetyChecker.allowsIssue(checks));
        assertFalse(IssueSafetyChecker.needsWarningReason(checks));
    }

    @Test
    public void onlyAReservedUnitCanBeIssued() {
        for (UnitStatus status : UnitStatus.values()) {
            IssueSnapshot s = good();
            IssueSnapshot snapshot = new IssueSnapshot(redCells(status, s.unit().expiresAt(), PATIENT), s.reservation(),
                    s.scannedPatientId(), s.profile(), s.rules(), s.secondCheck(), DIN);
            SafetyCheck check = byCode(IssueSafetyChecker.check(snapshot, CLOCK)).get("UNIT_STATUS");
            assertEquals(status.name(), status == UnitStatus.RESERVED ? CheckStatus.PASS : CheckStatus.FAIL,
                    check.status());
        }
    }

    @Test
    public void expiryBoundaryIsExclusive() {
        assertEquals(CheckStatus.FAIL, expiryAt(NOW).status());
        assertEquals(CheckStatus.FAIL, expiryAt(NOW.minusSeconds(1)).status());
        assertEquals(CheckStatus.PASS, expiryAt(NOW.plusSeconds(1)).status());
        assertEquals(CheckStatus.FAIL, expiryAt(null).status());
    }

    private static SafetyCheck expiryAt(Instant expiresAt) {
        IssueSnapshot s = good();
        IssueSnapshot snapshot = new IssueSnapshot(redCells(UnitStatus.RESERVED, expiresAt, PATIENT), s.reservation(),
                s.scannedPatientId(), s.profile(), s.rules(), s.secondCheck(), DIN);
        return byCode(IssueSafetyChecker.check(snapshot, CLOCK)).get("EXPIRY");
    }

    @Test
    public void theScannedPatientMustBeTheReservedPatient() {
        IssueSnapshot s = good();
        SafetyCheck wrongPatient = byCode(IssueSafetyChecker.check(new IssueSnapshot(s.unit(), s.reservation(),
                OTHER_PATIENT, s.profile(), s.rules(), s.secondCheck(), DIN), CLOCK)).get("PATIENT_MATCH");
        assertEquals(CheckStatus.FAIL, wrongPatient.status());
        assertEquals("bloodbank.check.patient.mismatch", wrongPatient.messageKey());

        SafetyCheck nobody = byCode(IssueSafetyChecker.check(
                new IssueSnapshot(s.unit(), s.reservation(), null, s.profile(), s.rules(), s.secondCheck(), DIN),
                CLOCK)).get("PATIENT_MATCH");
        assertEquals("bloodbank.check.patient.notIdentified", nobody.messageKey());

        SafetyCheck noReservation = byCode(IssueSafetyChecker
                .check(new IssueSnapshot(s.unit(), null, PATIENT, s.profile(), s.rules(), s.secondCheck(), DIN), CLOCK))
                .get("PATIENT_MATCH");
        assertEquals("bloodbank.check.patient.noReservation", noReservation.messageKey());

        // the unit itself names another patient than the reservation
        SafetyCheck unitDisagrees = byCode(IssueSafetyChecker
                .check(new IssueSnapshot(redCells(UnitStatus.RESERVED, s.unit().expiresAt(), OTHER_PATIENT),
                        s.reservation(), PATIENT, s.profile(), s.rules(), s.secondCheck(), DIN), CLOCK))
                .get("PATIENT_MATCH");
        assertEquals(CheckStatus.FAIL, unitDisagrees.status());

        ReservationFacts released = new ReservationFacts(7L, PATIENT, ReservationStatus.RELEASED, CROSSMATCH);
        assertEquals(CheckStatus.FAIL,
                byCode(IssueSafetyChecker.check(
                        new IssueSnapshot(s.unit(), released, PATIENT, s.profile(), s.rules(), s.secondCheck(), DIN),
                        CLOCK)).get("PATIENT_MATCH").status());
    }

    @Test
    public void crossmatchValidityBoundaryIsExclusive() {
        assertEquals(CheckStatus.FAIL, crossmatchCheck(crossmatch(true, NOW)).status());
        assertEquals("bloodbank.check.crossmatch.expired", crossmatchCheck(crossmatch(true, NOW)).messageKey());
        assertEquals(CheckStatus.PASS, crossmatchCheck(crossmatch(true, NOW.plusSeconds(1))).status());
    }

    @Test
    public void anIncompatibleOrUnvalidatedCrossmatchFails() {
        assertEquals("bloodbank.check.crossmatch.incompatible",
                crossmatchCheck(crossmatch(false, NOW.plus(Duration.ofHours(1)))).messageKey());

        IssueSnapshot s = good();
        // the reservation names a result that is not among the patient's validated
        // crossmatches
        SafetyCheck notValidated = byCode(IssueSafetyChecker.check(
                new IssueSnapshot(s.unit(), s.reservation(), PATIENT, profile(), s.rules(), s.secondCheck(), DIN),
                CLOCK)).get("CROSSMATCH");
        assertEquals("bloodbank.check.crossmatch.notValidated", notValidated.messageKey());

        SafetyCheck missing = byCode(IssueSafetyChecker.check(new IssueSnapshot(s.unit(), reservation(PATIENT, null),
                PATIENT, s.profile(), s.rules(), s.secondCheck(), DIN), CLOCK)).get("CROSSMATCH");
        assertEquals("bloodbank.check.crossmatch.missing", missing.messageKey());
    }

    @Test
    public void plateletsNeedNoCrossmatch() {
        IssueSnapshot s = good();
        UnitFacts platelets = new UnitFacts(1L, UnitStatus.RESERVED, ComponentClass.PLATELETS, false, Abo.A, RhD.NEG,
                NOW.plus(Duration.ofDays(2)), PATIENT, DIN);
        SafetyCheck check = byCode(IssueSafetyChecker.check(new IssueSnapshot(platelets, reservation(PATIENT, null),
                PATIENT, s.profile(), s.rules(), s.secondCheck(), DIN), CLOCK)).get("CROSSMATCH");
        assertEquals(CheckStatus.PASS, check.status());
        assertEquals("bloodbank.check.crossmatch.notRequired", check.messageKey());
    }

    private static SafetyCheck crossmatchCheck(CrossmatchResult result) {
        IssueSnapshot s = good();
        return byCode(IssueSafetyChecker.check(
                new IssueSnapshot(s.unit(), s.reservation(), PATIENT, profile(result), s.rules(), s.secondCheck(), DIN),
                CLOCK)).get("CROSSMATCH");
    }

    @Test
    public void everySecondCheckOutcomeButVerifiedStopsTheIssue() {
        for (SecondCheckOutcome outcome : SecondCheckOutcome.values()) {
            IssueSnapshot s = good();
            List<SafetyCheck> checks = IssueSafetyChecker.check(
                    new IssueSnapshot(s.unit(), s.reservation(), PATIENT, s.profile(), s.rules(), outcome, DIN), CLOCK);
            assertEquals(outcome.name(), outcome == SecondCheckOutcome.VERIFIED,
                    IssueSafetyChecker.allowsIssue(checks));
        }
        IssueSnapshot s = good();
        List<SafetyCheck> dryRun = IssueSafetyChecker
                .check(new IssueSnapshot(s.unit(), s.reservation(), PATIENT, s.profile(), s.rules(), null, DIN), CLOCK);
        assertEquals(CheckStatus.PENDING, byCode(dryRun).get("SECOND_PERSON").status());
        // a dry run with only the second person pending has nothing failed
        assertTrue(IssueSafetyChecker.nothingFailed(dryRun));
        assertFalse(IssueSafetyChecker.allowsIssue(dryRun));
    }

    @Test
    public void aWarningAllowsIssueButAsksForAReason() {
        PatientBloodProfile rhNegative = new PatientBloodProfile(PATIENT,
                new PatientGroup(Abo.A, RhD.NEG, true, false, 2, 2), List.of(), null, null, List.of(), List.of());
        UnitFacts rhPositivePlatelets = new UnitFacts(1L, UnitStatus.RESERVED, ComponentClass.PLATELETS, false, Abo.A,
                RhD.POS, NOW.plus(Duration.ofDays(2)), PATIENT, DIN);
        List<SafetyCheck> checks = IssueSafetyChecker
                .check(new IssueSnapshot(rhPositivePlatelets, reservation(PATIENT, null), PATIENT, rhNegative,
                        BloodBankRules.DEFAULTS, SecondCheckOutcome.VERIFIED, DIN), CLOCK);

        assertEquals(CheckStatus.WARN, byCode(checks).get("RHD").status());
        assertTrue(IssueSafetyChecker.allowsIssue(checks));
        assertTrue(IssueSafetyChecker.needsWarningReason(checks));

        List<SafetyCheck> blocked = IssueSafetyChecker.check(
                new IssueSnapshot(rhPositivePlatelets, reservation(PATIENT, null), PATIENT, rhNegative,
                        new BloodBankRules(true, RuleAction.BLOCK, RuleAction.BLOCK), SecondCheckOutcome.VERIFIED, DIN),
                CLOCK);
        assertFalse(IssueSafetyChecker.allowsIssue(blocked));
    }

    @Test
    public void anIncompatibleUnitIsRefusedEvenWithEverythingElseRight() {
        IssueSnapshot s = good();
        UnitFacts groupA = new UnitFacts(1L, UnitStatus.RESERVED, ComponentClass.RED_CELLS, true, Abo.B, RhD.NEG,
                NOW.plus(Duration.ofDays(5)), PATIENT, DIN);
        List<SafetyCheck> checks = IssueSafetyChecker.check(
                new IssueSnapshot(groupA, s.reservation(), PATIENT, s.profile(), s.rules(), s.secondCheck(), DIN),
                CLOCK);
        assertEquals(CheckStatus.FAIL, byCode(checks).get("ABO").status());
        assertFalse(IssueSafetyChecker.allowsIssue(checks));
    }

    @Test
    public void noProfileMeansNothingIsKnownAboutThePatient() {
        IssueSnapshot s = good();
        List<SafetyCheck> checks = IssueSafetyChecker.check(
                new IssueSnapshot(s.unit(), s.reservation(), PATIENT, null, s.rules(), s.secondCheck(), DIN), CLOCK);
        assertEquals(CheckStatus.FAIL, byCode(checks).get("GROUP_CONFIRMED").status());
        assertEquals(CheckStatus.FAIL, byCode(checks).get("CROSSMATCH").status());
        assertFalse(IssueSafetyChecker.allowsIssue(checks));
    }

    private static SafetyCheck unitMatch(String scanned) {
        IssueSnapshot s = good();
        return byCode(IssueSafetyChecker.check(new IssueSnapshot(s.unit(), s.reservation(), s.scannedPatientId(),
                s.profile(), s.rules(), s.secondCheck(), scanned), CLOCK)).get("UNIT_MATCH");
    }

    @Test
    public void theBagInHandMustBeTheUnitOnScreen() {
        // the DIN as a scan (with flags), as typed, and with its check character
        assertEquals(CheckStatus.PASS, unitMatch("=" + DIN + "00").status());
        assertEquals(CheckStatus.PASS, unitMatch(" " + DIN.toLowerCase() + " ").status());
        assertEquals(CheckStatus.PASS, unitMatch(DIN + Isbt128Parser.checkCharacter(DIN)).status());
        assertEquals("bloodbank.check.unitMatch.ok", unitMatch(DIN).messageKey());

        SafetyCheck otherBag = unitMatch("G123498654322");
        assertEquals(CheckStatus.FAIL, otherBag.status());
        assertEquals("bloodbank.check.unitMatch.mismatch", otherBag.messageKey());
        assertEquals(List.of("G123498654322", DIN), otherBag.args());

        assertEquals("bloodbank.check.unitMatch.notScanned", unitMatch(null).messageKey());
        assertEquals("bloodbank.check.unitMatch.notScanned", unitMatch("  ").messageKey());
        assertEquals("bloodbank.check.unitMatch.unreadable", unitMatch("not a din").messageKey());
        // a wrong check character is not taken as a match
        char wrong = Isbt128Parser.checkCharacter(DIN) == 'A' ? 'B' : 'A';
        assertEquals(CheckStatus.FAIL, unitMatch(DIN + wrong).status());
    }

    @Test
    public void anotherBagStopsTheIssueEvenWithEverythingElseRight() {
        IssueSnapshot s = good();
        List<SafetyCheck> checks = IssueSafetyChecker.check(new IssueSnapshot(s.unit(), s.reservation(),
                s.scannedPatientId(), s.profile(), s.rules(), s.secondCheck(), "G123498654322"), CLOCK);
        assertFalse(IssueSafetyChecker.allowsIssue(checks));
        assertFalse(IssueSafetyChecker.nothingFailed(checks));
        assertEquals(1, checks.stream().filter(c -> c.status() == CheckStatus.FAIL).count());
    }
}
