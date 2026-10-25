package org.openelisglobal.bloodbank.controller.rest;

/**
 * The blood bank's authorization tiers, in one place. The frontend's
 * {@code SecureRoute} roles mirror these.
 *
 * <p>
 * Every state-changing handler declares its own tier: Spring replaces the class
 * annotation with the method's rather than combining them.
 *
 * <p>
 * Known limit: OpenELIS roles are held per lab unit, and {@code hasRole} admits
 * a Results user of any lab unit. Restricting the module to people with a role
 * in the lab unit that runs the blood bank is left to a later version.
 */
public final class BloodBankGuards {

    /** Stock, unit detail, patient profile, candidates, trail. */
    public static final String READ = "hasAnyRole('RESULTS', 'VALIDATION', 'ADMIN')";

    /** Receive, stock actions, reserve, issue, return, transfused. */
    public static final String BENCH = "hasAnyRole('RESULTS', 'VALIDATION', 'ADMIN')";

    /** Releasing a unit from quarantine. */
    public static final String SUPERVISOR = "hasAnyRole('VALIDATION', 'ADMIN')";

    /** Settings and the product catalogue. */
    public static final String ADMIN = "hasRole('ADMIN')";

    private BloodBankGuards() {
    }
}
