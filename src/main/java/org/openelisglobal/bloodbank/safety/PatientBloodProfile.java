package org.openelisglobal.bloodbank.safety;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.List;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.Abo;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.RhD;

/**
 * What OpenELIS's validated results say about a patient, for transfusion: group
 * (and whether it is confirmed), antibody screen, DAT and crossmatches. Built
 * by the profile service; read by the compatibility rules and the issue checks.
 *
 * @param notConfigured the settings naming the tests that are still empty (e.g.
 *                      {@code bloodbank.test.abo}); their results cannot be
 *                      read
 */
public record PatientBloodProfile(Long patientId, PatientGroup group, List<TypingResult> typings,
        LabValue antibodyScreen, LabValue dat, List<CrossmatchResult> crossmatches, List<String> notConfigured) {

    public PatientBloodProfile {
        typings = typings == null ? List.of() : List.copyOf(typings);
        crossmatches = crossmatches == null ? List.of() : List.copyOf(crossmatches);
        notConfigured = notConfigured == null ? List.of() : List.copyOf(notConfigured);
    }

    /** One validated ABO or RhD result. */
    public record TypingResult(Kind kind, Long resultId, Long sampleId, String accessionNumber,
            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant collectedAt, String value) {

        public enum Kind {
            ABO, RHD
        }
    }

    /**
     * The patient's group as the results stand.
     *
     * @param abo            latest ABO result, or null if none or unreadable
     * @param rhd            latest RhD result, or null if none or unreadable
     * @param confirmed      the site's rule is met (two concordant samples by
     *                       default) and nothing disagrees
     * @param discrepancy    two results disagree, or one cannot be read as a group:
     *                       blocks every issue
     * @param aboSampleCount different samples with a validated ABO result
     * @param rhdSampleCount different samples with a validated RhD result
     */
    public record PatientGroup(Abo abo, RhD rhd, boolean confirmed, boolean discrepancy, int aboSampleCount,
            int rhdSampleCount) {

        public static final PatientGroup UNKNOWN = new PatientGroup(null, null, false, false, 0, 0);

        /** The ABO the rules may rely on: the confirmed group, else unknown. */
        public Abo trustedAbo() {
            return confirmed && !discrepancy ? abo : null;
        }

        /** The RhD the rules may rely on: the confirmed type, else unknown. */
        public RhD trustedRhd() {
            return confirmed && !discrepancy ? rhd : null;
        }
    }

    /** The latest validated value of a test, such as the antibody screen. */
    public record LabValue(String value, Long resultId, String accessionNumber,
            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant collectedAt) {
    }

    /**
     * A validated crossmatch result.
     *
     * @param validUntil   sample collection + the configured validity hours
     * @param compatible   the value is one of the configured compatible values
     * @param linkedUnitId the unit a live reservation already ties it to, if any
     */
    public record CrossmatchResult(Long resultId, Long sampleId, String accessionNumber,
            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant collectedAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant validUntil, String value, boolean compatible,
            Long linkedUnitId, String linkedDonationNumber) {

        public CrossmatchResult withLink(Long unitId, String donationNumber) {
            return new CrossmatchResult(resultId, sampleId, accessionNumber, collectedAt, validUntil, value, compatible,
                    unitId, donationNumber);
        }
    }

    /** The crossmatch result with this id, or null. */
    public CrossmatchResult crossmatch(Long resultId) {
        if (resultId == null) {
            return null;
        }
        return crossmatches.stream().filter(c -> resultId.equals(c.resultId())).findFirst().orElse(null);
    }
}
