package org.openelisglobal.bloodbank.safety;

import java.util.List;

/**
 * One line of the issue checklist.
 *
 * @param code       which check (see {@link IssueSafetyChecker} constants)
 * @param status     PASS, WARN (allowed, a reason is required at issue), FAIL,
 *                   or PENDING (not yet answerable, e.g. the second person
 *                   before they sign)
 * @param messageKey react-intl key the frontend shows
 * @param args       values for the message's placeholders
 */
public record SafetyCheck(String code, CheckStatus status, String messageKey, List<String> args) {

    public enum CheckStatus {
        PASS, WARN, FAIL, PENDING
    }

    public SafetyCheck {
        args = args == null ? List.of() : List.copyOf(args);
    }

    public static SafetyCheck pass(String code, String messageKey, String... args) {
        return new SafetyCheck(code, CheckStatus.PASS, messageKey, List.of(args));
    }

    public static SafetyCheck warn(String code, String messageKey, String... args) {
        return new SafetyCheck(code, CheckStatus.WARN, messageKey, List.of(args));
    }

    public static SafetyCheck fail(String code, String messageKey, String... args) {
        return new SafetyCheck(code, CheckStatus.FAIL, messageKey, List.of(args));
    }

    public static SafetyCheck pending(String code, String messageKey, String... args) {
        return new SafetyCheck(code, CheckStatus.PENDING, messageKey, List.of(args));
    }

    /** True for PASS and WARN: the check does not stop the issue. */
    public boolean passed() {
        return status == CheckStatus.PASS || status == CheckStatus.WARN;
    }
}
