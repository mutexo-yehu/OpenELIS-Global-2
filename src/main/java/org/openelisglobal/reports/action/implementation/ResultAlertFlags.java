package org.openelisglobal.reports.action.implementation;

import org.apache.commons.validator.GenericValidator;
import org.openelisglobal.internationalization.MessageUtil;
import org.openelisglobal.resultlimits.valueholder.ResultLimit;
import org.openelisglobal.resultvalidation.util.ValidationSignals;
import org.openelisglobal.typeoftestresult.service.TypeOfTestResultServiceImpl;

/**
 * The patient report's Alert letters for the critical tier (OGC-1121). The
 * legacy letters B / E say only "below / above normal"; BB / EE say the value
 * is beyond an authored critical bound, the same rule Results Entry and
 * Validation flag with (see {@link ValidationSignals#isCritical}).
 *
 * <p>
 * The letters are localized: {@code report.alert.letter.*} in the message files
 * (L / H / LL / HH in English, B / E / BB / EE in French). Where no message is
 * available the legacy letters below are printed.
 */
public final class ResultAlertFlags {

    public static final String CRITICALLY_BELOW = "BB";
    public static final String CRITICALLY_ABOVE = "EE";
    public static final String BELOW = "B";
    public static final String ABOVE = "E";
    public static final String ABNORMAL = "*";

    private ResultAlertFlags() {
    }

    public static String below() {
        return letter("report.alert.letter.below", BELOW);
    }

    public static String above() {
        return letter("report.alert.letter.above", ABOVE);
    }

    public static String criticallyBelow() {
        return letter("report.alert.letter.criticalBelow", CRITICALLY_BELOW);
    }

    public static String criticallyAbove() {
        return letter("report.alert.letter.criticalAbove", CRITICALLY_ABOVE);
    }

    private static String letter(String key, String legacyLetter) {
        try {
            String message = MessageUtil.getMessageIfPresent(key);
            return GenericValidator.isBlankOrNull(message) ? legacyLetter : message.trim();
        } catch (RuntimeException e) {
            // no message source (e.g. outside the application context)
            return legacyLetter;
        }
    }

    /**
     * The critically below / above letter, or an empty string when the value is not
     * critical or cannot be judged.
     */
    public static String criticalLetter(ResultLimit limit, String value) {
        if (limit == null || GenericValidator.isBlankOrNull(value)) {
            return "";
        }
        try {
            double numeric = Double.parseDouble(value.trim());
            if (Double.isFinite(limit.getLowCritical()) && numeric < limit.getLowCritical()) {
                return criticallyBelow();
            }
            if (Double.isFinite(limit.getHighCritical()) && numeric > limit.getHighCritical()) {
                return criticallyAbove();
            }
            return "";
        } catch (NumberFormatException e) {
            return "";
        }
    }

    /**
     * The Alert letter for one component of a multi-component result, judged
     * against that component's own range: critically below / above beyond a
     * critical bound, below / above outside the normal bounds, * for a select-list
     * answer other than the range's normal choice, empty otherwise. Multi-component
     * rows printed no letter at all, so an N2 Ct of 33 against 22 - 32 read as
     * unremarkable.
     */
    public static String componentLetter(ResultLimit limit, String resultType, String value) {
        if (limit == null || GenericValidator.isBlankOrNull(value)) {
            return "";
        }
        if (TypeOfTestResultServiceImpl.ResultType.isDictionaryVariant(resultType)) {
            return !GenericValidator.isBlankOrNull(limit.getDictionaryNormalId())
                    && !value.equals(limit.getDictionaryNormalId()) ? ABNORMAL : "";
        }
        if (!TypeOfTestResultServiceImpl.ResultType.NUMERIC.matches(resultType)) {
            return "";
        }
        String critical = criticalLetter(limit, value);
        if (!critical.isEmpty()) {
            return critical;
        }
        try {
            double numeric = Double.parseDouble(value.trim());
            if (Double.isFinite(limit.getLowNormal()) && numeric < limit.getLowNormal()) {
                return below();
            }
            if (Double.isFinite(limit.getHighNormal()) && numeric > limit.getHighNormal()) {
                return above();
            }
            return "";
        } catch (NumberFormatException e) {
            return "";
        }
    }
}
