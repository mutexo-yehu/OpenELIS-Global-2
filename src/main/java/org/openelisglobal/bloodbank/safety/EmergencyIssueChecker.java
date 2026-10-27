package org.openelisglobal.bloodbank.safety;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.openelisglobal.bloodbank.safety.IssueSafetyChecker.SecondCheckOutcome;
import org.openelisglobal.bloodbank.safety.IssueSafetyChecker.UnitFacts;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.Abo;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.ComponentClass;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.RhD;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.UnitStatus;

/**
 * The checks for an emergency issue: a unit given before the patient's group
 * and crossmatch are known, so only units every patient can receive are
 * allowed, group O red cells and group AB plasma. Pure, like
 * {@link IssueSafetyChecker}: the dry run and the check inside the issuing
 * transaction are the same code.
 *
 * <p>
 * What a normal issue checks and an emergency issue still checks: the unit is
 * in stock and available, unexpired, the bag in hand is this unit, the scanned
 * patient is the patient on screen, and the second check under the site's
 * setting. What it does not: the patient's confirmed group, a reservation, a
 * crossmatch. The lab checks those afterwards (the retrospective check).
 */
public final class EmergencyIssueChecker {

    public static final String PRODUCT = "EMERGENCY_PRODUCT";
    public static final String RHD = "EMERGENCY_RHD";

    /** The statement recorded when O RhD positive red cells are given. */
    public static final String RHD_POSITIVE_ACKNOWLEDGEMENT = "O RhD negative not available / patient not a female of"
            + " childbearing potential";

    private EmergencyIssueChecker() {
    }

    /**
     * @param patientId               the patient on screen, who receives the unit
     * @param scannedPatientId        the patient the scanned identifier resolved
     *                                to, or null
     * @param rhdPositiveAcknowledged the acknowledgement for O RhD positive red
     *                                cells was given
     * @param secondCheck             NOT_PROVIDED in a dry run
     * @param collectorName           null in a dry run
     */
    public record EmergencySnapshot(UnitFacts unit, Long patientId, Long scannedPatientId, String scannedDonationNumber,
            boolean rhdPositiveAcknowledged, SecondCheckOutcome secondCheck, SecondCheckMode secondCheckMode,
            String collectorName) {

        public EmergencySnapshot {
            secondCheckMode = secondCheckMode == null ? SecondCheckMode.REQUIRED : secondCheckMode;
        }
    }

    public static List<SafetyCheck> check(EmergencySnapshot snapshot, Clock clock) {
        Instant now = clock.instant();
        UnitFacts unit = snapshot.unit();
        List<SafetyCheck> checks = new ArrayList<>();
        checks.add(unitStatus(unit, snapshot.patientId()));
        checks.add(IssueSafetyChecker.unitMatch(unit, snapshot.scannedDonationNumber()));
        checks.add(IssueSafetyChecker.expiry(unit, now));
        checks.add(patientMatch(snapshot.patientId(), snapshot.scannedPatientId()));
        checks.add(product(unit.componentClass(), unit.abo()));
        checks.add(rhd(unit.componentClass(), unit.rhd(), snapshot.rhdPositiveAcknowledged()));
        checks.add(IssueSafetyChecker.secondPerson(snapshot.secondCheck(), snapshot.secondCheckMode(),
                snapshot.collectorName()));
        return checks;
    }

    /** Only these may go out before group and crossmatch. */
    public static boolean allowedProduct(ComponentClass componentClass, Abo abo) {
        return (componentClass == ComponentClass.RED_CELLS && abo == Abo.O)
                || (componentClass == ComponentClass.PLASMA && abo == Abo.AB);
    }

    /** True for O RhD positive red cells: the acknowledgement is then due. */
    public static boolean needsRhdAcknowledgement(ComponentClass componentClass, RhD rhd) {
        return componentClass == ComponentClass.RED_CELLS && rhd == RhD.POS;
    }

    /**
     * In stock and free. A unit reserved for this patient already has its
     * crossmatch, so it goes through the normal issue; one reserved for anyone else
     * is not this patient's.
     */
    static SafetyCheck unitStatus(UnitFacts unit, Long patientId) {
        if (unit.status() == UnitStatus.AVAILABLE) {
            return SafetyCheck.pass(IssueSafetyChecker.UNIT_STATUS, "bloodbank.emergency.check.unitStatus.available");
        }
        if (unit.status() == UnitStatus.RESERVED && patientId != null && patientId.equals(unit.patientId())) {
            return SafetyCheck.fail(IssueSafetyChecker.UNIT_STATUS,
                    "bloodbank.emergency.check.unitStatus.reservedForThisPatient");
        }
        return SafetyCheck.fail(IssueSafetyChecker.UNIT_STATUS, "bloodbank.emergency.check.unitStatus.notAvailable",
                String.valueOf(unit.status()));
    }

    static SafetyCheck patientMatch(Long patientId, Long scannedPatientId) {
        if (scannedPatientId == null) {
            return SafetyCheck.fail(IssueSafetyChecker.PATIENT_MATCH, "bloodbank.check.patient.notIdentified");
        }
        if (!scannedPatientId.equals(patientId)) {
            return SafetyCheck.fail(IssueSafetyChecker.PATIENT_MATCH, "bloodbank.emergency.check.patient.mismatch");
        }
        return SafetyCheck.pass(IssueSafetyChecker.PATIENT_MATCH, "bloodbank.emergency.check.patient.ok");
    }

    static SafetyCheck product(ComponentClass componentClass, Abo abo) {
        if (componentClass == ComponentClass.RED_CELLS && abo == Abo.O) {
            return SafetyCheck.pass(PRODUCT, "bloodbank.emergency.check.product.groupORedCells");
        }
        if (componentClass == ComponentClass.PLASMA && abo == Abo.AB) {
            return SafetyCheck.pass(PRODUCT, "bloodbank.emergency.check.product.groupABPlasma");
        }
        return SafetyCheck.fail(PRODUCT, "bloodbank.emergency.check.product.notAllowed", String.valueOf(componentClass),
                String.valueOf(abo));
    }

    static SafetyCheck rhd(ComponentClass componentClass, RhD rhd, boolean acknowledged) {
        if (componentClass != ComponentClass.RED_CELLS) {
            return SafetyCheck.pass(RHD, "bloodbank.emergency.check.rhd.notApplicable");
        }
        if (rhd == RhD.NEG) {
            return SafetyCheck.pass(RHD, "bloodbank.emergency.check.rhd.negative");
        }
        if (acknowledged) {
            return SafetyCheck.pass(RHD, "bloodbank.emergency.check.rhd.positiveAcknowledged");
        }
        return SafetyCheck.fail(RHD, "bloodbank.emergency.check.rhd.positiveNeedsAcknowledgement");
    }
}
