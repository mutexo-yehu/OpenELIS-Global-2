package org.openelisglobal.bloodbank.safety;

import java.util.Locale;

/**
 * The clinical rules a site chooses. Defaults are the conservative ones,
 * pending the laboratory's own transfusion policy.
 *
 * @param twoSampleGroup               a group is confirmed only by concordant
 *                                     results on two different samples
 * @param rhdNegPatientRhdPosPlatelets RhD-positive platelets for an
 *                                     RhD-negative (or untyped) patient
 * @param unconfirmedGroupORedCells    group O red cells for a patient whose
 *                                     group is not confirmed
 */
public record BloodBankRules(boolean twoSampleGroup, RuleAction rhdNegPatientRhdPosPlatelets,
        RuleAction unconfirmedGroupORedCells) {

    public static final BloodBankRules DEFAULTS = new BloodBankRules(true, RuleAction.WARN, RuleAction.BLOCK);

    public BloodBankRules {
        rhdNegPatientRhdPosPlatelets = rhdNegPatientRhdPosPlatelets == null ? RuleAction.WARN
                : rhdNegPatientRhdPosPlatelets;
        unconfirmedGroupORedCells = unconfirmedGroupORedCells == null ? RuleAction.BLOCK : unconfirmedGroupORedCells;
    }

    /** What a rule does when its situation arises. */
    public enum RuleAction {
        ALLOW, WARN, BLOCK;

        /** Reads a stored setting; anything unreadable falls back to the default. */
        public static RuleAction parse(String value, RuleAction fallback) {
            if (value == null) {
                return fallback;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }
}
