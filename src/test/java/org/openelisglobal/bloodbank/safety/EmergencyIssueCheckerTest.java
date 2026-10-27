package org.openelisglobal.bloodbank.safety;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.Test;
import org.openelisglobal.bloodbank.safety.EmergencyIssueChecker.EmergencySnapshot;
import org.openelisglobal.bloodbank.safety.IssueSafetyChecker.SecondCheckOutcome;
import org.openelisglobal.bloodbank.safety.IssueSafetyChecker.UnitFacts;
import org.openelisglobal.bloodbank.safety.SafetyCheck.CheckStatus;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.Abo;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.ComponentClass;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.RhD;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.UnitStatus;

/**
 * The emergency checklist: only group O red cells and group AB plasma, O RhD
 * positive only with the acknowledgement, and everything a normal issue checks
 * about the unit, the bag, the patient and the second person.
 */
public class EmergencyIssueCheckerTest {

    private static final Instant NOW = Instant.parse("2026-10-27T14:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Long PATIENT = 501L;
    private static final String DIN = "G123498654321";

    private static UnitFacts unit(ComponentClass component, Abo abo, RhD rhd, UnitStatus status, Long patientId,
            Instant expires) {
        return new UnitFacts(1L, status, component, component.carriesRedCells(), abo, rhd, expires, patientId, DIN);
    }

    private static UnitFacts oNeg() {
        return unit(ComponentClass.RED_CELLS, Abo.O, RhD.NEG, UnitStatus.AVAILABLE, null, NOW.plus(Duration.ofDays(5)));
    }

    private static List<SafetyCheck> check(UnitFacts unit, boolean acknowledged, SecondCheckOutcome second) {
        return EmergencyIssueChecker.check(new EmergencySnapshot(unit, PATIENT, PATIENT, DIN, acknowledged, second,
                SecondCheckMode.REQUIRED, "Porter Example"), CLOCK);
    }

    private static SafetyCheck find(List<SafetyCheck> checks, String code) {
        return checks.stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow();
    }

    @Test
    public void groupONegativeRedCellsPassEveryCheck() {
        List<SafetyCheck> checks = check(oNeg(), false, SecondCheckOutcome.VERIFIED);

        assertTrue(String.valueOf(checks), IssueSafetyChecker.allowsIssue(checks));
        assertEquals(List.of("UNIT_STATUS", "UNIT_MATCH", "EXPIRY", "PATIENT_MATCH", "EMERGENCY_PRODUCT",
                "EMERGENCY_RHD", "SECOND_PERSON"), checks.stream().map(SafetyCheck::code).toList());
        assertFalse("no ABO, crossmatch or group check", checks.stream().anyMatch(c -> c.code().equals("CROSSMATCH")));
    }

    @Test
    public void onlyGroupORedCellsAndGroupABPlasmaAreAllowed() {
        for (ComponentClass component : ComponentClass.values()) {
            for (Abo abo : Abo.values()) {
                boolean allowed = (component == ComponentClass.RED_CELLS && abo == Abo.O)
                        || (component == ComponentClass.PLASMA && abo == Abo.AB);
                SafetyCheck product = find(
                        check(unit(component, abo, RhD.NEG, UnitStatus.AVAILABLE, null, NOW.plus(Duration.ofDays(5))),
                                false, SecondCheckOutcome.VERIFIED),
                        EmergencyIssueChecker.PRODUCT);
                assertEquals(component + " " + abo, allowed ? CheckStatus.PASS : CheckStatus.FAIL, product.status());
                assertEquals(allowed, EmergencyIssueChecker.allowedProduct(component, abo));
                if (!allowed) {
                    assertEquals("bloodbank.emergency.check.product.notAllowed", product.messageKey());
                    assertEquals(List.of(component.name(), abo.name()), product.args());
                }
            }
        }
    }

    @Test
    public void groupORhDPositiveRedCellsNeedTheAcknowledgement() {
        UnitFacts oPos = unit(ComponentClass.RED_CELLS, Abo.O, RhD.POS, UnitStatus.AVAILABLE, null,
                NOW.plus(Duration.ofDays(5)));

        SafetyCheck without = find(check(oPos, false, SecondCheckOutcome.VERIFIED), EmergencyIssueChecker.RHD);
        SafetyCheck with = find(check(oPos, true, SecondCheckOutcome.VERIFIED), EmergencyIssueChecker.RHD);

        assertEquals(CheckStatus.FAIL, without.status());
        assertEquals("bloodbank.emergency.check.rhd.positiveNeedsAcknowledgement", without.messageKey());
        assertEquals(CheckStatus.PASS, with.status());
        assertEquals("bloodbank.emergency.check.rhd.positiveAcknowledged", with.messageKey());
        assertTrue(EmergencyIssueChecker.needsRhdAcknowledgement(ComponentClass.RED_CELLS, RhD.POS));
        assertFalse(EmergencyIssueChecker.needsRhdAcknowledgement(ComponentClass.RED_CELLS, RhD.NEG));
        assertFalse(EmergencyIssueChecker.needsRhdAcknowledgement(ComponentClass.PLASMA, RhD.POS));
    }

    @Test
    public void groupABPlasmaOfEitherRhDNeedsNoAcknowledgement() {
        for (RhD rhd : RhD.values()) {
            List<SafetyCheck> checks = check(
                    unit(ComponentClass.PLASMA, Abo.AB, rhd, UnitStatus.AVAILABLE, null, NOW.plus(Duration.ofDays(5))),
                    false, SecondCheckOutcome.VERIFIED);
            assertTrue(rhd + " " + checks, IssueSafetyChecker.allowsIssue(checks));
        }
    }

    @Test
    public void aUnitThatIsNotFreeInStockIsRefused() {
        for (UnitStatus status : UnitStatus.values()) {
            if (status == UnitStatus.AVAILABLE) {
                continue;
            }
            SafetyCheck unitStatus = find(
                    check(unit(ComponentClass.RED_CELLS, Abo.O, RhD.NEG, status, 777L, NOW.plus(Duration.ofDays(5))),
                            false, SecondCheckOutcome.VERIFIED),
                    "UNIT_STATUS");
            assertEquals(status.name(), CheckStatus.FAIL, unitStatus.status());
            assertEquals("bloodbank.emergency.check.unitStatus.notAvailable", unitStatus.messageKey());
            assertEquals(List.of(status.name()), unitStatus.args());
        }
        // reserved for this very patient: it has a crossmatch, so the normal issue
        SafetyCheck own = find(check(unit(ComponentClass.RED_CELLS, Abo.O, RhD.NEG, UnitStatus.RESERVED, PATIENT,
                NOW.plus(Duration.ofDays(5))), false, SecondCheckOutcome.VERIFIED), "UNIT_STATUS");
        assertEquals(CheckStatus.FAIL, own.status());
        assertEquals("bloodbank.emergency.check.unitStatus.reservedForThisPatient", own.messageKey());
    }

    @Test
    public void anExpiredUnitIsRefused() {
        List<SafetyCheck> checks = check(
                unit(ComponentClass.RED_CELLS, Abo.O, RhD.NEG, UnitStatus.AVAILABLE, null, NOW.minusSeconds(60)), false,
                SecondCheckOutcome.VERIFIED);
        assertEquals(CheckStatus.FAIL, find(checks, "EXPIRY").status());
        assertFalse(IssueSafetyChecker.allowsIssue(checks));
    }

    @Test
    public void theBagInHandMustBeThisUnit() {
        for (String scanned : new String[] { null, " ", "G123498654322", "not-a-din" }) {
            List<SafetyCheck> checks = EmergencyIssueChecker.check(new EmergencySnapshot(oNeg(), PATIENT, PATIENT,
                    scanned, false, SecondCheckOutcome.VERIFIED, SecondCheckMode.REQUIRED, "Porter"), CLOCK);
            assertEquals(String.valueOf(scanned), CheckStatus.FAIL, find(checks, "UNIT_MATCH").status());
        }
    }

    @Test
    public void theScannedPatientMustBeThePatientOnScreen() {
        SafetyCheck other = find(EmergencyIssueChecker.check(new EmergencySnapshot(oNeg(), PATIENT, 777L, DIN, false,
                SecondCheckOutcome.VERIFIED, SecondCheckMode.REQUIRED, "Porter"), CLOCK), "PATIENT_MATCH");
        SafetyCheck nobody = find(EmergencyIssueChecker.check(new EmergencySnapshot(oNeg(), PATIENT, null, DIN, false,
                SecondCheckOutcome.VERIFIED, SecondCheckMode.REQUIRED, "Porter"), CLOCK), "PATIENT_MATCH");

        assertEquals(CheckStatus.FAIL, other.status());
        assertEquals("bloodbank.emergency.check.patient.mismatch", other.messageKey());
        assertEquals(CheckStatus.FAIL, nobody.status());
        assertEquals("bloodbank.check.patient.notIdentified", nobody.messageKey());
    }

    @Test
    public void theSecondCheckFollowsTheSiteSettingAsInANormalIssue() {
        SafetyCheck pending = find(check(oNeg(), false, SecondCheckOutcome.NOT_PROVIDED), "SECOND_PERSON");
        SafetyCheck refused = find(check(oNeg(), false, SecondCheckOutcome.INVALID_CREDENTIALS), "SECOND_PERSON");
        SafetyCheck single = find(
                EmergencyIssueChecker.check(new EmergencySnapshot(oNeg(), PATIENT, PATIENT, DIN, false,
                        SecondCheckOutcome.NOT_PROVIDED, SecondCheckMode.OPTIONAL_WITH_REASON, "Porter"), CLOCK),
                "SECOND_PERSON");
        SafetyCheck collector = find(
                EmergencyIssueChecker.check(new EmergencySnapshot(oNeg(), PATIENT, PATIENT, DIN, false,
                        SecondCheckOutcome.NOT_PROVIDED, SecondCheckMode.COLLECTOR_CONFIRMS, "Porter"), CLOCK),
                "SECOND_PERSON");

        assertEquals(CheckStatus.PENDING, pending.status());
        assertEquals(CheckStatus.FAIL, refused.status());
        assertEquals(CheckStatus.WARN, single.status());
        assertEquals(CheckStatus.PASS, collector.status());
    }
}
