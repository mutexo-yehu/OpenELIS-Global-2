package org.openelisglobal.reports.action.implementation;

import org.apache.commons.validator.GenericValidator;
import org.openelisglobal.internationalization.MessageUtil;
import org.openelisglobal.resultlimits.valueholder.ResultLimit;
import org.openelisglobal.resultvalidation.util.ValidationSignals;
import org.openelisglobal.typeoftestresult.service.TypeOfTestResultServiceImpl;

/**
 * The patient report's Alert flags (OGC-1121). The internal codes B / E say
 * only "below / above normal"; BB / EE say the value is beyond an authored
 * critical bound, the same rule Results Entry and Validation flag with (see
 * {@link ValidationSignals#isCritical}); * marks an abnormal select-list
 * answer.
 *
 * <p>
 * The codes are what report rows and the print queue carry and compare (the PDF
 * report maps them to an arrow and a letter). Only printed text uses the
 * localized letters from {@link #letterFor}: {@code report.alert.letter.*} in
 * the message files (L / H / LL / HH in English, B / E / BB / EE in French).
 * Where no message is available the code itself is printed.
 */
public final class ResultAlertFlags {

    public static final String CRITICALLY_BELOW = "BB";
    public static final String CRITICALLY_ABOVE = "EE";
    public static final String BELOW = "B";
    public static final String ABOVE = "E";
    public static final String ABNORMAL = "*";

    private ResultAlertFlags() {
    }

    /**
     * The localized letter to print for a flag code; an empty string for no flag,
     * and the code itself for one without a letter of its own (*).
     */
    public static String letterFor(String code) {
        if (code == null) {
            return "";
        }
        switch (code) {
        case BELOW:
            return letter("report.alert.letter.below", BELOW);
        case ABOVE:
            return letter("report.alert.letter.above", ABOVE);
        case CRITICALLY_BELOW:
            return letter("report.alert.letter.criticalBelow", CRITICALLY_BELOW);
        case CRITICALLY_ABOVE:
            return letter("report.alert.letter.criticalAbove", CRITICALLY_ABOVE);
        default:
            return code;
        }
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
     * The critically below / above code, or an empty string when the value is not
     * critical or cannot be judged.
     */
    public static String criticalCode(ResultLimit limit, String value) {
        if (limit == null || GenericValidator.isBlankOrNull(value)) {
            return "";
        }
        try {
            double numeric = Double.parseDouble(value.trim());
            if (Double.isFinite(limit.getLowCritical()) && numeric < limit.getLowCritical()) {
                return CRITICALLY_BELOW;
            }
            if (Double.isFinite(limit.getHighCritical()) && numeric > limit.getHighCritical()) {
                return CRITICALLY_ABOVE;
            }
            return "";
        } catch (NumberFormatException e) {
            return "";
        }
    }

    /**
     * The Alert code for one component of a multi-component result, judged against
     * that component's own range: critically below / above beyond a critical bound,
     * below / above outside the normal bounds, * for a select-list answer other
     * than the range's normal choice, empty otherwise. Multi-component rows printed
     * no letter at all, so an N2 Ct of 33 against 22 - 32 read as unremarkable.
     */
    public static String componentCode(ResultLimit limit, String resultType, String value) {
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
        String critical = criticalCode(limit, value);
        if (!critical.isEmpty()) {
            return critical;
        }
        try {
            double numeric = Double.parseDouble(value.trim());
            if (Double.isFinite(limit.getLowNormal()) && numeric < limit.getLowNormal()) {
                return BELOW;
            }
            if (Double.isFinite(limit.getHighNormal()) && numeric > limit.getHighNormal()) {
                return ABOVE;
            }
            return "";
        } catch (NumberFormatException e) {
            return "";
        }
    }
}
