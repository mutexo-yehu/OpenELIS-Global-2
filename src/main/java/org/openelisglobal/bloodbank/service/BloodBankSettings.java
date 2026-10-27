package org.openelisglobal.bloodbank.service;

import java.util.List;
import org.openelisglobal.bloodbank.safety.BloodBankRules;
import org.openelisglobal.bloodbank.safety.BloodBankRules.RuleAction;
import org.openelisglobal.bloodbank.safety.SecondCheckMode;

/**
 * The blood bank's site settings ({@code site_information} rows named
 * {@code bloodbank.*}).
 *
 * @param testAbo                      catalogue (local) code of the ABO test
 * @param testRhd                      ... of the RhD test
 * @param testAntibodyScreen           ... of the antibody screen
 * @param testDat                      ... of the direct antiglobulin test
 * @param testCrossmatch               ... of the crossmatch
 * @param crossmatchCompatibleValues   crossmatch values that mean compatible
 * @param crossmatchValidHours         from the crossmatch sample's collection
 * @param expiryWarnHours              stock board warning
 * @param returnWindowMinutes          an issued unit returned later is
 *                                     discarded
 * @param twoSampleGroup               see {@link BloodBankRules}
 * @param rhdNegPatientRhdPosPlatelets see {@link BloodBankRules}
 * @param unconfirmedGroupORedCells    see {@link BloodBankRules}
 * @param secondCheck                  see {@link SecondCheckMode}
 */
public record BloodBankSettings(String testAbo, String testRhd, String testAntibodyScreen, String testDat,
        String testCrossmatch, List<String> crossmatchCompatibleValues, int crossmatchValidHours, int expiryWarnHours,
        int returnWindowMinutes, boolean twoSampleGroup, RuleAction rhdNegPatientRhdPosPlatelets,
        RuleAction unconfirmedGroupORedCells, SecondCheckMode secondCheck) {

    public static final String TEST_ABO = "bloodbank.test.abo";
    public static final String TEST_RHD = "bloodbank.test.rhd";
    public static final String TEST_ANTIBODY_SCREEN = "bloodbank.test.antibodyScreen";
    public static final String TEST_DAT = "bloodbank.test.dat";
    public static final String TEST_CROSSMATCH = "bloodbank.test.crossmatch";
    public static final String CROSSMATCH_COMPATIBLE_VALUES = "bloodbank.crossmatch.compatibleValues";
    public static final String CROSSMATCH_VALID_HOURS = "bloodbank.crossmatch.validHours";
    public static final String EXPIRY_WARN_HOURS = "bloodbank.expiry.warnHours";
    public static final String RETURN_WINDOW_MINUTES = "bloodbank.return.windowMinutes";
    public static final String RULE_TWO_SAMPLE = "bloodbank.rule.twoSampleGroup";
    public static final String RULE_RHD_PLATELETS = "bloodbank.rule.rhdNegPatientRhdPosPlatelets";
    public static final String RULE_UNCONFIRMED_O = "bloodbank.rule.unconfirmedGroupORedCells";
    public static final String SECOND_CHECK = "bloodbank.secondCheck";

    /** What applies when a setting row is missing or unreadable. */
    public static final BloodBankSettings DEFAULTS = new BloodBankSettings("", "", "", "", "", List.of("Compatible"),
            72, 48, 30, true, RuleAction.WARN, RuleAction.BLOCK, SecondCheckMode.REQUIRED);

    public BloodBankSettings {
        crossmatchCompatibleValues = crossmatchCompatibleValues == null ? List.of()
                : List.copyOf(crossmatchCompatibleValues);
        secondCheck = secondCheck == null ? SecondCheckMode.REQUIRED : secondCheck;
    }

    public BloodBankRules rules() {
        return new BloodBankRules(twoSampleGroup, rhdNegPatientRhdPosPlatelets, unconfirmedGroupORedCells);
    }
}
