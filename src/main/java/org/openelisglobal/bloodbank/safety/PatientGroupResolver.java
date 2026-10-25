package org.openelisglobal.bloodbank.safety;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.openelisglobal.bloodbank.safety.PatientBloodProfile.PatientGroup;
import org.openelisglobal.bloodbank.safety.PatientBloodProfile.TypingResult;
import org.openelisglobal.bloodbank.safety.PatientBloodProfile.TypingResult.Kind;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.Abo;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.RhD;

/**
 * Works out a patient's group from their validated ABO and RhD results.
 *
 * <ul>
 * <li>The group shown is the latest ABO and the latest RhD.</li>
 * <li>A discrepancy is any two results that disagree, or a result that cannot
 * be read as a group at all; it blocks every issue until the lab resolves
 * it.</li>
 * <li>With the two-sample rule (the default), the group is confirmed when both
 * ABO and RhD have results on at least two different samples and nothing
 * disagrees. Without it, one sample is enough.</li>
 * </ul>
 */
public final class PatientGroupResolver {

    private PatientGroupResolver() {
    }

    public static PatientGroup resolve(List<TypingResult> typings, boolean twoSampleRule) {
        if (typings == null || typings.isEmpty()) {
            return PatientGroup.UNKNOWN;
        }
        Comparator<TypingResult> latestFirst = Comparator
                .comparing(TypingResult::collectedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(TypingResult::resultId, Comparator.nullsLast(Comparator.reverseOrder()));
        List<TypingResult> abo = typings.stream().filter(t -> t.kind() == Kind.ABO).sorted(latestFirst).toList();
        List<TypingResult> rhd = typings.stream().filter(t -> t.kind() == Kind.RHD).sorted(latestFirst).toList();

        boolean discrepancy = disagree(abo.stream().map(t -> (Object) Abo.parse(t.value())).toList())
                || disagree(rhd.stream().map(t -> (Object) RhD.parse(t.value())).toList());

        Abo latestAbo = abo.isEmpty() ? null : Abo.parse(abo.get(0).value());
        RhD latestRhd = rhd.isEmpty() ? null : RhD.parse(rhd.get(0).value());
        int aboSamples = distinctSamples(abo);
        int rhdSamples = distinctSamples(rhd);
        int needed = twoSampleRule ? 2 : 1;
        boolean confirmed = !discrepancy && latestAbo != null && latestRhd != null && aboSamples >= needed
                && rhdSamples >= needed;
        return new PatientGroup(latestAbo, latestRhd, confirmed, discrepancy, aboSamples, rhdSamples);
    }

    /** True when a value is unreadable (null) or two values differ. */
    private static boolean disagree(List<Object> parsed) {
        if (parsed.stream().anyMatch(Objects::isNull)) {
            return true;
        }
        return parsed.stream().distinct().count() > 1;
    }

    private static int distinctSamples(List<TypingResult> results) {
        Set<Object> samples = new HashSet<>();
        for (TypingResult result : results) {
            // a result without a sample id still counts once, never twice
            samples.add(result.sampleId() != null ? result.sampleId() : "result-" + result.resultId());
        }
        return samples.size();
    }
}
