package org.openelisglobal.bloodbank.safety;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.openelisglobal.bloodbank.safety.BloodBankRules.RuleAction;
import org.openelisglobal.bloodbank.safety.PatientBloodProfile.PatientGroup;
import org.openelisglobal.bloodbank.safety.SafetyCheck.CheckStatus;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.Abo;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.ComponentClass;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.RhD;

/**
 * The compatibility rules as tables, written out by hand from transfusion
 * practice, then checked for every component × donor ABO × donor RhD × patient
 * ABO × patient RhD.
 */
public class BloodCompatibilityTest {

    private static final Abo[] GROUPS = { Abo.O, Abo.A, Abo.B, Abo.AB };

    // rows: donor O, A, B, AB; columns: patient O, A, B, AB. P = pass, F = fail, W
    // = warn
    private static final String[] RED_CELLS_ABO = { "PPPP", "FPFP", "FFPP", "FFFP" };
    private static final String[] WHOLE_BLOOD_ABO = { "PFFF", "FPFF", "FFPF", "FFFP" };
    private static final String[] PLASMA_ABO = { "PFFF", "PPFF", "PFPF", "PPPP" };
    private static final String[] PLATELETS_ABO = { "PWWW", "WPWW", "WWPW", "WWWP" };

    private static final Map<ComponentClass, String[]> ABO_TABLES = Map.of(ComponentClass.RED_CELLS, RED_CELLS_ABO,
            ComponentClass.WHOLE_BLOOD, WHOLE_BLOOD_ABO, ComponentClass.PLASMA, PLASMA_ABO, ComponentClass.CRYO,
            PLASMA_ABO, ComponentClass.PLATELETS, PLATELETS_ABO);

    // rows: donor POS, NEG; columns: patient POS, NEG (default rules)
    private static final Map<ComponentClass, String[]> RHD_TABLES = Map.of(ComponentClass.RED_CELLS,
            new String[] { "PF", "PP" }, ComponentClass.WHOLE_BLOOD, new String[] { "PF", "PP" },
            ComponentClass.PLATELETS, new String[] { "PW", "PP" }, ComponentClass.PLASMA, new String[] { "PP", "PP" },
            ComponentClass.CRYO, new String[] { "PP", "PP" });

    private static PatientGroup confirmed(Abo abo, RhD rhd) {
        return new PatientGroup(abo, rhd, true, false, 2, 2);
    }

    private static CheckStatus expected(char code) {
        return switch (code) {
        case 'P' -> CheckStatus.PASS;
        case 'W' -> CheckStatus.WARN;
        default -> CheckStatus.FAIL;
        };
    }

    @Test
    public void everyComponentDonorAndPatientCombinationFollowsTheTables() {
        int combinations = 0;
        for (ComponentClass component : ComponentClass.values()) {
            for (int d = 0; d < GROUPS.length; d++) {
                for (RhD donorRhd : RhD.values()) {
                    for (int p = 0; p < GROUPS.length; p++) {
                        for (RhD patientRhd : RhD.values()) {
                            List<SafetyCheck> checks = BloodCompatibility.check(component, GROUPS[d], donorRhd,
                                    confirmed(GROUPS[p], patientRhd), BloodBankRules.DEFAULTS);
                            String label = component + " donor " + GROUPS[d] + donorRhd + " patient " + GROUPS[p]
                                    + patientRhd;
                            assertEquals(label, BloodCompatibility.GROUP_CONFIRMED, checks.get(0).code());
                            assertEquals(label, CheckStatus.PASS, checks.get(0).status());
                            assertEquals(label + " ABO", expected(ABO_TABLES.get(component)[d].charAt(p)),
                                    checks.get(1).status());
                            int donorRow = donorRhd == RhD.POS ? 0 : 1;
                            int patientColumn = patientRhd == RhD.POS ? 0 : 1;
                            assertEquals(label + " RhD",
                                    expected(RHD_TABLES.get(component)[donorRow].charAt(patientColumn)),
                                    checks.get(2).status());
                            combinations++;
                        }
                    }
                }
            }
        }
        assertEquals(5 * 4 * 2 * 4 * 2, combinations);
    }

    @Test
    public void rhdPositivePlateletsForAnRhdNegativePatientFollowTheSitesRule() {
        PatientGroup negative = confirmed(Abo.A, RhD.NEG);
        assertEquals(CheckStatus.PASS, rhdStatus(ComponentClass.PLATELETS, negative, RuleAction.ALLOW));
        assertEquals(CheckStatus.WARN, rhdStatus(ComponentClass.PLATELETS, negative, RuleAction.WARN));
        assertEquals(CheckStatus.FAIL, rhdStatus(ComponentClass.PLATELETS, negative, RuleAction.BLOCK));
        // the platelet rule never relaxes red cells
        assertEquals(CheckStatus.FAIL, rhdStatus(ComponentClass.RED_CELLS, negative, RuleAction.ALLOW));
    }

    private static CheckStatus rhdStatus(ComponentClass component, PatientGroup patient, RuleAction plateletRule) {
        BloodBankRules rules = new BloodBankRules(true, plateletRule, RuleAction.BLOCK);
        return BloodCompatibility.check(component, Abo.A, RhD.POS, patient, rules).get(2).status();
    }

    @Test
    public void anUnconfirmedGroupIsTreatedAsUnknown() {
        // latest result says A POS, but one sample only: nothing relies on it
        PatientGroup unconfirmed = new PatientGroup(Abo.A, RhD.POS, false, false, 1, 1);
        BloodBankRules allowO = new BloodBankRules(true, RuleAction.WARN, RuleAction.ALLOW);

        List<SafetyCheck> aPos = BloodCompatibility.check(ComponentClass.RED_CELLS, Abo.A, RhD.POS, unconfirmed,
                allowO);
        assertEquals(CheckStatus.FAIL, aPos.get(0).status());
        assertEquals(CheckStatus.FAIL, aPos.get(1).status());
        assertEquals("bloodbank.check.abo.patientUnknown", aPos.get(1).messageKey());
        assertEquals(CheckStatus.FAIL, aPos.get(2).status());
        assertEquals("bloodbank.check.rhd.patientUnknown", aPos.get(2).messageKey());

        List<SafetyCheck> oNeg = BloodCompatibility.check(ComponentClass.RED_CELLS, Abo.O, RhD.NEG, unconfirmed,
                allowO);
        assertTrue(oNeg.stream().allMatch(c -> c.status() == CheckStatus.PASS));

        // O POS red cells: ABO passes, RhD does not (an untyped patient is RhD
        // negative)
        List<SafetyCheck> oPos = BloodCompatibility.check(ComponentClass.RED_CELLS, Abo.O, RhD.POS, unconfirmed,
                allowO);
        assertEquals(CheckStatus.PASS, oPos.get(1).status());
        assertEquals(CheckStatus.FAIL, oPos.get(2).status());

        // AB plasma suits anyone
        assertEquals(CheckStatus.PASS,
                BloodCompatibility.check(ComponentClass.PLASMA, Abo.AB, RhD.POS, unconfirmed, allowO).get(1).status());
        assertEquals(CheckStatus.FAIL,
                BloodCompatibility.check(ComponentClass.PLASMA, Abo.A, RhD.POS, unconfirmed, allowO).get(1).status());
        assertEquals(CheckStatus.WARN, BloodCompatibility
                .check(ComponentClass.PLATELETS, Abo.A, RhD.NEG, unconfirmed, allowO).get(1).status());
        assertEquals(CheckStatus.FAIL, BloodCompatibility
                .check(ComponentClass.WHOLE_BLOOD, Abo.O, RhD.NEG, unconfirmed, allowO).get(1).status());
    }

    @Test
    public void groupORedCellsForAnUnconfirmedGroupFollowTheSitesRule() {
        PatientGroup unconfirmed = new PatientGroup(Abo.B, RhD.NEG, false, false, 1, 1);
        assertEquals(CheckStatus.FAIL, groupStatus(ComponentClass.RED_CELLS, Abo.O, unconfirmed, RuleAction.BLOCK));
        assertEquals(CheckStatus.WARN, groupStatus(ComponentClass.RED_CELLS, Abo.O, unconfirmed, RuleAction.WARN));
        assertEquals(CheckStatus.PASS, groupStatus(ComponentClass.RED_CELLS, Abo.O, unconfirmed, RuleAction.ALLOW));
        // only group O, only red cells
        assertEquals(CheckStatus.FAIL, groupStatus(ComponentClass.RED_CELLS, Abo.B, unconfirmed, RuleAction.ALLOW));
        assertEquals(CheckStatus.FAIL, groupStatus(ComponentClass.WHOLE_BLOOD, Abo.O, unconfirmed, RuleAction.ALLOW));
        assertEquals(CheckStatus.FAIL, groupStatus(ComponentClass.PLASMA, Abo.O, unconfirmed, RuleAction.ALLOW));
        // the default is BLOCK
        assertEquals(RuleAction.BLOCK, BloodBankRules.DEFAULTS.unconfirmedGroupORedCells());
    }

    private static CheckStatus groupStatus(ComponentClass component, Abo donor, PatientGroup patient, RuleAction rule) {
        BloodBankRules rules = new BloodBankRules(true, RuleAction.WARN, rule);
        return BloodCompatibility.check(component, donor, RhD.NEG, patient, rules).get(0).status();
    }

    @Test
    public void aDiscrepancyFailsWhateverTheRules() {
        PatientGroup discrepant = new PatientGroup(Abo.O, RhD.NEG, false, true, 2, 2);
        BloodBankRules lenient = new BloodBankRules(false, RuleAction.ALLOW, RuleAction.ALLOW);
        for (ComponentClass component : ComponentClass.values()) {
            SafetyCheck group = BloodCompatibility.check(component, Abo.O, RhD.NEG, discrepant, lenient).get(0);
            assertEquals(component.name(), CheckStatus.FAIL, group.status());
            assertEquals("bloodbank.check.group.discrepancy", group.messageKey());
        }
    }

    @Test
    public void reservationIsAllowedWhileTheGroupAwaitsConfirmationButNotWithADiscrepancy() {
        PatientGroup unconfirmed = new PatientGroup(Abo.O, RhD.NEG, false, false, 1, 1);
        PatientGroup discrepant = new PatientGroup(Abo.O, RhD.NEG, false, true, 2, 2);

        List<SafetyCheck> waiting = BloodCompatibility.check(ComponentClass.RED_CELLS, Abo.O, RhD.NEG, unconfirmed,
                BloodBankRules.DEFAULTS);
        assertEquals(CheckStatus.FAIL, waiting.get(0).status());
        assertTrue(BloodCompatibility.allowsReservation(waiting));

        assertFalse(BloodCompatibility.allowsReservation(BloodCompatibility.check(ComponentClass.RED_CELLS, Abo.O,
                RhD.NEG, discrepant, BloodBankRules.DEFAULTS)));
        assertFalse(BloodCompatibility.allowsReservation(BloodCompatibility.check(ComponentClass.RED_CELLS, Abo.A,
                RhD.NEG, confirmed(Abo.O, RhD.NEG), BloodBankRules.DEFAULTS)));
    }

    @Test
    public void aUnitWithNoGroupFails() {
        List<SafetyCheck> checks = BloodCompatibility.check(ComponentClass.RED_CELLS, null, null,
                confirmed(Abo.AB, RhD.POS), BloodBankRules.DEFAULTS);
        assertEquals(CheckStatus.FAIL, checks.get(1).status());
        assertEquals(CheckStatus.FAIL, checks.get(2).status());
    }

    @Test
    public void aMissingProfileCountsAsAnUnknownGroup() {
        List<SafetyCheck> checks = BloodCompatibility.check(ComponentClass.RED_CELLS, Abo.A, RhD.NEG, null,
                BloodBankRules.DEFAULTS);
        assertEquals(CheckStatus.FAIL, checks.get(0).status());
        assertEquals(CheckStatus.FAIL, checks.get(1).status());
    }
}
