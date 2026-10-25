package org.openelisglobal.bloodbank.safety;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.Test;
import org.openelisglobal.bloodbank.safety.PatientBloodProfile.PatientGroup;
import org.openelisglobal.bloodbank.safety.PatientBloodProfile.TypingResult;
import org.openelisglobal.bloodbank.safety.PatientBloodProfile.TypingResult.Kind;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.Abo;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.RhD;

public class PatientGroupResolverTest {

    private static final Instant DAY1 = Instant.parse("2026-10-20T08:00:00Z");
    private static final Instant DAY2 = Instant.parse("2026-10-21T08:00:00Z");

    private static TypingResult abo(long resultId, long sampleId, Instant collected, String value) {
        return new TypingResult(Kind.ABO, resultId, sampleId, "LAB-" + sampleId, collected, value);
    }

    private static TypingResult rhd(long resultId, long sampleId, Instant collected, String value) {
        return new TypingResult(Kind.RHD, resultId, sampleId, "LAB-" + sampleId, collected, value);
    }

    @Test
    public void twoConcordantSamplesConfirmTheGroup() {
        PatientGroup group = PatientGroupResolver.resolve(List.of(abo(1, 10, DAY1, "A"), rhd(2, 10, DAY1, "Positive"),
                abo(3, 11, DAY2, "A"), rhd(4, 11, DAY2, "Positive")), true);

        assertEquals(Abo.A, group.abo());
        assertEquals(RhD.POS, group.rhd());
        assertTrue(group.confirmed());
        assertFalse(group.discrepancy());
        assertEquals(2, group.aboSampleCount());
        assertEquals(Abo.A, group.trustedAbo());
    }

    @Test
    public void oneSampleIsNotEnoughUnderTheTwoSampleRule() {
        List<TypingResult> oneSample = List.of(abo(1, 10, DAY1, "O"), rhd(2, 10, DAY1, "Negative"));

        PatientGroup strict = PatientGroupResolver.resolve(oneSample, true);
        assertFalse(strict.confirmed());
        assertEquals(Abo.O, strict.abo());
        assertNull(strict.trustedAbo());
        assertNull(strict.trustedRhd());

        assertTrue(PatientGroupResolver.resolve(oneSample, false).confirmed());
    }

    @Test
    public void twoResultsOnTheSameSampleCountOnce() {
        PatientGroup group = PatientGroupResolver.resolve(List.of(abo(1, 10, DAY1, "O"), abo(2, 10, DAY1, "O"),
                rhd(3, 10, DAY1, "Negative"), rhd(4, 10, DAY1, "Negative")), true);

        assertEquals(1, group.aboSampleCount());
        assertFalse(group.confirmed());
    }

    @Test
    public void disagreeingResultsAreADiscrepancy() {
        PatientGroup group = PatientGroupResolver.resolve(List.of(abo(1, 10, DAY1, "A"), rhd(2, 10, DAY1, "Positive"),
                abo(3, 11, DAY2, "O"), rhd(4, 11, DAY2, "Positive")), true);

        assertTrue(group.discrepancy());
        assertFalse(group.confirmed());
        // the latest is still shown
        assertEquals(Abo.O, group.abo());
        assertNull(group.trustedAbo());
    }

    @Test
    public void disagreeingRhdIsADiscrepancyToo() {
        PatientGroup group = PatientGroupResolver.resolve(List.of(abo(1, 10, DAY1, "B"), rhd(2, 10, DAY1, "Positive"),
                abo(3, 11, DAY2, "B"), rhd(4, 11, DAY2, "Negative")), false);

        assertTrue(group.discrepancy());
        assertFalse(group.confirmed());
    }

    @Test
    public void anUnreadableResultIsADiscrepancy() {
        PatientGroup group = PatientGroupResolver.resolve(List.of(abo(1, 10, DAY1, "A"), rhd(2, 10, DAY1, "Weak D"),
                abo(3, 11, DAY2, "A"), rhd(4, 11, DAY2, "Positive")), true);

        assertTrue(group.discrepancy());
        assertFalse(group.confirmed());
    }

    @Test
    public void rhdMissingMeansNotConfirmed() {
        PatientGroup group = PatientGroupResolver.resolve(List.of(abo(1, 10, DAY1, "AB"), abo(2, 11, DAY2, "AB")),
                true);

        assertEquals(Abo.AB, group.abo());
        assertNull(group.rhd());
        assertFalse(group.confirmed());
        assertFalse(group.discrepancy());
    }

    @Test
    public void noResultsIsUnknown() {
        assertEquals(PatientGroup.UNKNOWN, PatientGroupResolver.resolve(List.of(), true));
        assertEquals(PatientGroup.UNKNOWN, PatientGroupResolver.resolve(null, true));
    }

    @Test
    public void theLatestResultIsShownWhateverTheOrderGiven() {
        PatientGroup group = PatientGroupResolver.resolve(List.of(abo(3, 11, DAY2, "B"), abo(1, 10, DAY1, "A")), true);
        assertEquals(Abo.B, group.abo());
    }
}
