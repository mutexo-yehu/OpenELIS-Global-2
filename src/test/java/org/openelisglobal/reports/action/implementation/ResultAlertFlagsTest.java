package org.openelisglobal.reports.action.implementation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;
import org.openelisglobal.resultlimits.valueholder.ResultLimit;

/**
 * OGC-1121 — the patient report's Alert column gains a critical tier: BB / EE
 * beyond an authored critical bound, nothing otherwise, so the legacy B / E
 * letters keep meaning "outside normal".
 */
public class ResultAlertFlagsTest {

    private static ResultLimit criticalBounds(double low, double high) {
        ResultLimit limit = new ResultLimit();
        limit.setLowCritical(low);
        limit.setHighCritical(high);
        return limit;
    }

    @Test
    public void beyondTheHighCriticalBound_isEE() {
        assertEquals("EE", ResultAlertFlags.criticalCode(criticalBounds(2d, 150d), "200"));
    }

    @Test
    public void belowTheLowCriticalBound_isBB() {
        assertEquals("BB", ResultAlertFlags.criticalCode(criticalBounds(2d, 150d), "1"));
    }

    @Test
    public void merelyAbnormal_getsNoCriticalCode() {
        assertEquals("", ResultAlertFlags.criticalCode(criticalBounds(2d, 150d), "120"));
    }

    @Test
    public void unauthoredBounds_neverFire() {
        ResultLimit unauthored = criticalBounds(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        assertEquals("", ResultAlertFlags.criticalCode(unauthored, "200"));
        assertEquals("", ResultAlertFlags.criticalCode(unauthored, "-5"));
    }

    @Test
    public void nothingToJudge_isEmpty() {
        assertEquals("", ResultAlertFlags.criticalCode(null, "200"));
        assertEquals("", ResultAlertFlags.criticalCode(criticalBounds(2d, 150d), ""));
        assertEquals("", ResultAlertFlags.criticalCode(criticalBounds(2d, 150d), "n/a"));
    }

    private static ResultLimit componentRange(double lowNormal, double highNormal) {
        ResultLimit limit = new ResultLimit();
        limit.setLowNormal(lowNormal);
        limit.setHighNormal(highNormal);
        limit.setLowCritical(Double.NEGATIVE_INFINITY);
        limit.setHighCritical(Double.POSITIVE_INFINITY);
        return limit;
    }

    // OGC-1266: a multi-component result (COVID PCR N2 Ct 33 against 22 - 32)
    // printed no Alert letter on the patient report.
    @Test
    public void componentCode_numericOutsideItsOwnRange_isBOrE() {
        assertEquals("E", ResultAlertFlags.componentCode(componentRange(22d, 32d), "N", "33"));
        assertEquals("B", ResultAlertFlags.componentCode(componentRange(22d, 32d), "N", "21.9"));
        assertEquals("", ResultAlertFlags.componentCode(componentRange(22d, 32d), "N", "32"));
    }

    @Test
    public void componentCode_criticalBeatsNormal() {
        ResultLimit range = componentRange(22d, 32d);
        range.setHighCritical(40d);
        assertEquals("EE", ResultAlertFlags.componentCode(range, "N", "41"));
    }

    @Test
    public void componentCode_selectListAnswerOtherThanTheNormalChoice_isStar() {
        ResultLimit range = new ResultLimit();
        range.setDictionaryNormalId("1334");
        assertEquals("*", ResultAlertFlags.componentCode(range, "D", "1335"));
        assertEquals("", ResultAlertFlags.componentCode(range, "D", "1334"));
        assertEquals("", ResultAlertFlags.componentCode(new ResultLimit(), "D", "1335"));
    }

    @Test
    public void componentCode_nothingToJudge_isEmpty() {
        assertEquals("", ResultAlertFlags.componentCode(null, "N", "33"));
        assertEquals("", ResultAlertFlags.componentCode(componentRange(22d, 32d), "N", ""));
        assertEquals("", ResultAlertFlags.componentCode(componentRange(22d, 32d), "A", "33"));
    }

    // The judges return the internal codes whatever the language, so report rows,
    // the PDF's flag column and the print queue can compare them. A localized
    // letter (H, HH) stored as the code matched nothing: no flag text and no
    // critical marking on the PDF report.
    @Test
    public void codesAreTheInternalCodesWhateverTheLanguagePrints() {
        assertEquals(ResultAlertFlags.CRITICALLY_ABOVE, ResultAlertFlags.criticalCode(criticalBounds(2d, 150d), "200"));
        assertEquals(ResultAlertFlags.CRITICALLY_BELOW, ResultAlertFlags.criticalCode(criticalBounds(2d, 150d), "1"));
        assertEquals(ResultAlertFlags.ABOVE, ResultAlertFlags.componentCode(componentRange(22d, 32d), "N", "33"));
        assertEquals(ResultAlertFlags.BELOW, ResultAlertFlags.componentCode(componentRange(22d, 32d), "N", "21.9"));
    }

    @Test
    public void letterFor_mapsEachCodeToAPrintedLetterAndLeavesNoFlagEmpty() {
        for (String code : new String[] { ResultAlertFlags.BELOW, ResultAlertFlags.ABOVE,
                ResultAlertFlags.CRITICALLY_BELOW, ResultAlertFlags.CRITICALLY_ABOVE }) {
            assertFalse(code, ResultAlertFlags.letterFor(code).isEmpty());
        }
        assertEquals(ResultAlertFlags.ABNORMAL, ResultAlertFlags.letterFor(ResultAlertFlags.ABNORMAL));
        assertEquals("", ResultAlertFlags.letterFor(""));
        assertEquals("", ResultAlertFlags.letterFor(null));
    }
}
