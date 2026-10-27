package org.openelisglobal.bloodbank.safety;

import java.util.Locale;

/**
 * How a site checks an issue a second time ({@code bloodbank.secondCheck}).
 *
 * <ul>
 * <li>{@link #REQUIRED} (the default): a second, different user signs in with
 * their own credentials.</li>
 * <li>{@link #OPTIONAL_WITH_REASON}: a second user may sign in; when none is
 * available, one person may issue, with a reason that is kept in the
 * trail.</li>
 * <li>{@link #COLLECTOR_CONFIRMS}: the person collecting the unit confirms it,
 * and their recorded name is the confirmation; no second sign-in.</li>
 * </ul>
 */
public enum SecondCheckMode {
    REQUIRED, OPTIONAL_WITH_REASON, COLLECTOR_CONFIRMS;

    /**
     * Reads a stored setting; anything missing or unreadable is the strictest mode.
     */
    public static SecondCheckMode parse(String value) {
        if (value == null || value.isBlank()) {
            return REQUIRED;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return REQUIRED;
        }
    }

    /** The stored value if it names a mode exactly, else null. */
    public static SecondCheckMode strictParse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
