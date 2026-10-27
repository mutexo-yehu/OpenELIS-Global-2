package org.openelisglobal.bloodbank.safety;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.openelisglobal.bloodbank.isbt.Isbt128Parser;
import org.openelisglobal.bloodbank.safety.PatientBloodProfile.CrossmatchResult;
import org.openelisglobal.bloodbank.safety.SafetyCheck.CheckStatus;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.Abo;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.ComponentClass;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.ReservationStatus;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.RhD;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.UnitStatus;

/**
 * Every check that stands between a reserved unit and its issue to a patient.
 * Pure: it reads only the snapshot it is given and the clock, so the dry run
 * the screen shows and the check repeated inside the issuing transaction are
 * the same code.
 *
 * <p>
 * There is no override here. Emergency issue of group O red cells and group AB
 * plasma before group and crossmatch is a separate path with its own checks,
 * {@link EmergencyIssueChecker}; nothing in this class is relaxed for it.
 */
public final class IssueSafetyChecker {

    public static final String UNIT_STATUS = "UNIT_STATUS";
    public static final String UNIT_MATCH = "UNIT_MATCH";
    public static final String EXPIRY = "EXPIRY";
    public static final String PATIENT_MATCH = "PATIENT_MATCH";
    public static final String CROSSMATCH = "CROSSMATCH";
    public static final String SECOND_PERSON = "SECOND_PERSON";

    private IssueSafetyChecker() {
    }

    /** What the checks read. */
    public record UnitFacts(Long id, UnitStatus status, ComponentClass componentClass, boolean needsCrossmatch, Abo abo,
            RhD rhd, Instant expiresAt, Long patientId, String donationNumber) {
    }

    /** The unit's live reservation; null when it has none. */
    public record ReservationFacts(Long id, Long patientId, ReservationStatus status, Long crossmatchResultId) {
    }

    /**
     * @param scannedPatientId      the patient the scanned lab number or patient
     *                              identifier resolved to, or null if it resolved
     *                              to nobody
     * @param secondCheck           the outcome of the second person's
     *                              re-authentication; NOT_PROVIDED in a dry run
     * @param scannedDonationNumber the donation number scanned or typed from the
     *                              bag in hand, as received (a DIN scan, the 13
     *                              characters, or the 13 plus the check character)
     * @param secondCheckMode       the site's second-check setting
     * @param collectorName         the person collecting the unit; null in a dry
     *                              run
     */
    public record IssueSnapshot(UnitFacts unit, ReservationFacts reservation, Long scannedPatientId,
            PatientBloodProfile profile, BloodBankRules rules, SecondCheckOutcome secondCheck,
            String scannedDonationNumber, SecondCheckMode secondCheckMode, String collectorName) {

        public IssueSnapshot {
            secondCheckMode = secondCheckMode == null ? SecondCheckMode.REQUIRED : secondCheckMode;
        }

        /** A snapshot under the default setting: a second person is required. */
        public IssueSnapshot(UnitFacts unit, ReservationFacts reservation, Long scannedPatientId,
                PatientBloodProfile profile, BloodBankRules rules, SecondCheckOutcome secondCheck,
                String scannedDonationNumber) {
            this(unit, reservation, scannedPatientId, profile, rules, secondCheck, scannedDonationNumber,
                    SecondCheckMode.REQUIRED, null);
        }
    }

    /** How the second person's re-authentication ended. */
    public enum SecondCheckOutcome {
        NOT_PROVIDED, VERIFIED, SAME_USER, UNKNOWN_USER, INACTIVE_USER, INVALID_CREDENTIALS, UNAVAILABLE
    }

    public static List<SafetyCheck> check(IssueSnapshot snapshot, Clock clock) {
        Instant now = clock.instant();
        UnitFacts unit = snapshot.unit();
        List<SafetyCheck> checks = new ArrayList<>();
        checks.add(unitStatus(unit));
        checks.add(unitMatch(unit, snapshot.scannedDonationNumber()));
        checks.add(expiry(unit, now));
        checks.add(patientMatch(unit, snapshot.reservation(), snapshot.scannedPatientId()));
        checks.addAll(BloodCompatibility.check(unit.componentClass(), unit.abo(), unit.rhd(),
                snapshot.profile() == null ? null : snapshot.profile().group(), snapshot.rules()));
        checks.add(crossmatch(unit, snapshot.reservation(), snapshot.profile(), now));
        checks.add(secondPerson(snapshot.secondCheck(), snapshot.secondCheckMode(), snapshot.collectorName()));
        return checks;
    }

    /** True when nothing failed and nothing is still pending. */
    public static boolean allowsIssue(List<SafetyCheck> checks) {
        return checks.stream().allMatch(SafetyCheck::passed);
    }

    /** True when at least one check passed with a warning, so a reason is due. */
    public static boolean needsWarningReason(List<SafetyCheck> checks) {
        return checks.stream().anyMatch(c -> c.status() == CheckStatus.WARN);
    }

    static SafetyCheck unitStatus(UnitFacts unit) {
        if (unit.status() == UnitStatus.RESERVED) {
            return SafetyCheck.pass(UNIT_STATUS, "bloodbank.check.unitStatus.reserved");
        }
        return SafetyCheck.fail(UNIT_STATUS, "bloodbank.check.unitStatus.notReserved", String.valueOf(unit.status()));
    }

    /**
     * The bag in hand must be the unit on screen: its scanned donation number must
     * be this unit's. A missing or unreadable scan fails, as does a wrong check
     * character.
     */
    static SafetyCheck unitMatch(UnitFacts unit, String scannedDonationNumber) {
        if (scannedDonationNumber == null || scannedDonationNumber.isBlank()) {
            return SafetyCheck.fail(UNIT_MATCH, "bloodbank.check.unitMatch.notScanned");
        }
        List<String> errors = new ArrayList<>();
        String scanned = Isbt128Parser.typedDonationNumber(scannedDonationNumber, errors);
        if (scanned == null || !errors.isEmpty()) {
            return SafetyCheck.fail(UNIT_MATCH, "bloodbank.check.unitMatch.unreadable", scannedDonationNumber.trim());
        }
        if (!scanned.equals(unit.donationNumber())) {
            return SafetyCheck.fail(UNIT_MATCH, "bloodbank.check.unitMatch.mismatch", scanned,
                    String.valueOf(unit.donationNumber()));
        }
        return SafetyCheck.pass(UNIT_MATCH, "bloodbank.check.unitMatch.ok", scanned);
    }

    /**
     * True when nothing failed; pending checks (the second person in a dry run) are
     * not counted.
     */
    public static boolean nothingFailed(List<SafetyCheck> checks) {
        return checks.stream().noneMatch(c -> c.status() == CheckStatus.FAIL);
    }

    public static SafetyCheck expiry(UnitFacts unit, Instant now) {
        if (unit.expiresAt() != null && unit.expiresAt().isAfter(now)) {
            return SafetyCheck.pass(EXPIRY, "bloodbank.check.expiry.ok", unit.expiresAt().toString());
        }
        return SafetyCheck.fail(EXPIRY, "bloodbank.check.expiry.expired", String.valueOf(unit.expiresAt()));
    }

    static SafetyCheck patientMatch(UnitFacts unit, ReservationFacts reservation, Long scannedPatientId) {
        if (reservation == null || reservation.status() != ReservationStatus.ACTIVE) {
            return SafetyCheck.fail(PATIENT_MATCH, "bloodbank.check.patient.noReservation");
        }
        if (scannedPatientId == null) {
            return SafetyCheck.fail(PATIENT_MATCH, "bloodbank.check.patient.notIdentified");
        }
        if (!scannedPatientId.equals(reservation.patientId()) || !scannedPatientId.equals(unit.patientId())) {
            return SafetyCheck.fail(PATIENT_MATCH, "bloodbank.check.patient.mismatch");
        }
        return SafetyCheck.pass(PATIENT_MATCH, "bloodbank.check.patient.ok");
    }

    static SafetyCheck crossmatch(UnitFacts unit, ReservationFacts reservation, PatientBloodProfile profile,
            Instant now) {
        if (!unit.needsCrossmatch()) {
            return SafetyCheck.pass(CROSSMATCH, "bloodbank.check.crossmatch.notRequired");
        }
        if (reservation == null || reservation.crossmatchResultId() == null) {
            return SafetyCheck.fail(CROSSMATCH, "bloodbank.check.crossmatch.missing");
        }
        // the profile lists only this patient's validated crossmatches
        CrossmatchResult result = profile == null ? null : profile.crossmatch(reservation.crossmatchResultId());
        if (result == null) {
            return SafetyCheck.fail(CROSSMATCH, "bloodbank.check.crossmatch.notValidated");
        }
        if (!result.compatible()) {
            return SafetyCheck.fail(CROSSMATCH, "bloodbank.check.crossmatch.incompatible", result.value());
        }
        if (result.validUntil() == null || !result.validUntil().isAfter(now)) {
            return SafetyCheck.fail(CROSSMATCH, "bloodbank.check.crossmatch.expired",
                    String.valueOf(result.validUntil()));
        }
        return SafetyCheck.pass(CROSSMATCH, "bloodbank.check.crossmatch.ok", result.validUntil().toString());
    }

    /**
     * The second check under the site's setting. Credentials that were given are
     * always checked, whatever the mode: a refused second sign-in fails the issue.
     * Without credentials, REQUIRED waits for them, OPTIONAL_WITH_REASON warns (so
     * the issue needs a reason, kept in the trail), and COLLECTOR_CONFIRMS passes
     * once the collector is named.
     */
    static SafetyCheck secondPerson(SecondCheckOutcome outcome, SecondCheckMode mode, String collectorName) {
        SecondCheckOutcome value = outcome == null ? SecondCheckOutcome.NOT_PROVIDED : outcome;
        if (value == SecondCheckOutcome.NOT_PROVIDED) {
            switch (mode == null ? SecondCheckMode.REQUIRED : mode) {
            case OPTIONAL_WITH_REASON:
                return SafetyCheck.warn(SECOND_PERSON, "bloodbank.check.secondPerson.singlePerson");
            case COLLECTOR_CONFIRMS:
                if (collectorName == null || collectorName.isBlank()) {
                    return SafetyCheck.pending(SECOND_PERSON, "bloodbank.check.secondPerson.collectorPending");
                }
                return SafetyCheck.pass(SECOND_PERSON, "bloodbank.check.secondPerson.collectorConfirms",
                        collectorName.trim());
            default:
                return SafetyCheck.pending(SECOND_PERSON, "bloodbank.check.secondPerson.required");
            }
        }
        return switch (value) {
        case VERIFIED -> SafetyCheck.pass(SECOND_PERSON, "bloodbank.check.secondPerson.verified");
        case SAME_USER -> SafetyCheck.fail(SECOND_PERSON, "bloodbank.check.secondPerson.sameUser");
        case UNKNOWN_USER, INVALID_CREDENTIALS ->
            SafetyCheck.fail(SECOND_PERSON, "bloodbank.check.secondPerson.invalid");
        case INACTIVE_USER -> SafetyCheck.fail(SECOND_PERSON, "bloodbank.check.secondPerson.inactive");
        case UNAVAILABLE, NOT_PROVIDED -> SafetyCheck.fail(SECOND_PERSON, "bloodbank.check.secondPerson.unavailable");
        };
    }
}
