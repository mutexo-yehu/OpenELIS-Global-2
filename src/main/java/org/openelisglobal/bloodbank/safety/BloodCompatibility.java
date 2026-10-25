package org.openelisglobal.bloodbank.safety;

import java.util.List;
import org.openelisglobal.bloodbank.safety.BloodBankRules.RuleAction;
import org.openelisglobal.bloodbank.safety.PatientBloodProfile.PatientGroup;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.Abo;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.ComponentClass;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.RhD;

/**
 * Whether a unit of a given component and group suits a patient: the group
 * confirmation, ABO and RhD lines of the issue checklist. The Reserve screen
 * uses the same function to grey out unsuitable units with the reason.
 *
 * <p>
 * Only a confirmed group is relied on. While a patient's group is not confirmed
 * it is treated as unknown: only group O red cells, AB plasma and RhD-negative
 * red cells pass ABO and RhD, and whether even those may be issued is the
 * {@link BloodBankRules#unconfirmedGroupORedCells()} rule.
 */
public final class BloodCompatibility {

    public static final String GROUP_CONFIRMED = "GROUP_CONFIRMED";
    public static final String ABO = "ABO";
    public static final String RHD = "RHD";
    static final String MSG_UNCONFIRMED = "bloodbank.check.group.unconfirmed";

    private BloodCompatibility() {
    }

    /**
     * Whether a unit may be reserved: nothing failed, except that a group not yet
     * confirmed does not stop a reservation (it stops the issue). A discrepancy
     * stops both.
     */
    public static boolean allowsReservation(List<SafetyCheck> checks) {
        return checks.stream().noneMatch(c -> c.status() == SafetyCheck.CheckStatus.FAIL
                && !(GROUP_CONFIRMED.equals(c.code()) && MSG_UNCONFIRMED.equals(c.messageKey())));
    }

    /** The group-confirmation, ABO and RhD checks, in that order. */
    public static List<SafetyCheck> check(ComponentClass component, Abo donorAbo, RhD donorRhd, PatientGroup patient,
            BloodBankRules rules) {
        PatientGroup group = patient == null ? PatientGroup.UNKNOWN : patient;
        return List.of(groupConfirmed(component, donorAbo, group, rules), abo(component, donorAbo, group.trustedAbo()),
                rhd(component, donorRhd, group.trustedRhd(), rules));
    }

    static SafetyCheck groupConfirmed(ComponentClass component, Abo donorAbo, PatientGroup group,
            BloodBankRules rules) {
        if (group.discrepancy()) {
            return SafetyCheck.fail(GROUP_CONFIRMED, "bloodbank.check.group.discrepancy");
        }
        if (group.confirmed()) {
            return SafetyCheck.pass(GROUP_CONFIRMED, "bloodbank.check.group.confirmed",
                    String.valueOf(group.aboSampleCount()));
        }
        if (component == ComponentClass.RED_CELLS && donorAbo == Abo.O) {
            RuleAction action = rules.unconfirmedGroupORedCells();
            if (action == RuleAction.ALLOW) {
                return SafetyCheck.pass(GROUP_CONFIRMED, "bloodbank.check.group.unconfirmedGroupO");
            }
            if (action == RuleAction.WARN) {
                return SafetyCheck.warn(GROUP_CONFIRMED, "bloodbank.check.group.unconfirmedGroupO");
            }
        }
        return SafetyCheck.fail(GROUP_CONFIRMED, MSG_UNCONFIRMED, String.valueOf(group.aboSampleCount()));
    }

    static SafetyCheck abo(ComponentClass component, Abo donor, Abo patient) {
        String donorText = donor == null ? "" : donor.name();
        String patientText = patient == null ? "" : patient.name();
        if (donor == null) {
            return SafetyCheck.fail(ABO, "bloodbank.check.abo.unitUnknown");
        }
        switch (component) {
        case RED_CELLS:
            return redCellsSuit(donor, patient)
                    ? SafetyCheck.pass(ABO, "bloodbank.check.abo.ok", donorText, patientText)
                    : SafetyCheck.fail(ABO,
                            patient == null ? "bloodbank.check.abo.patientUnknown" : "bloodbank.check.abo.incompatible",
                            donorText, patientText);
        case WHOLE_BLOOD:
            if (patient == null) {
                return SafetyCheck.fail(ABO, "bloodbank.check.abo.patientUnknown", donorText, patientText);
            }
            return donor == patient ? SafetyCheck.pass(ABO, "bloodbank.check.abo.ok", donorText, patientText)
                    : SafetyCheck.fail(ABO, "bloodbank.check.abo.notIdentical", donorText, patientText);
        case PLASMA:
        case CRYO:
            return plasmaSuits(donor, patient) ? SafetyCheck.pass(ABO, "bloodbank.check.abo.ok", donorText, patientText)
                    : SafetyCheck.fail(ABO,
                            patient == null ? "bloodbank.check.abo.patientUnknown" : "bloodbank.check.abo.incompatible",
                            donorText, patientText);
        case PLATELETS:
            if (patient == null) {
                return SafetyCheck.warn(ABO, "bloodbank.check.abo.plateletsPatientUnknown", donorText, patientText);
            }
            return donor == patient ? SafetyCheck.pass(ABO, "bloodbank.check.abo.ok", donorText, patientText)
                    : SafetyCheck.warn(ABO, "bloodbank.check.abo.plateletsNotIdentical", donorText, patientText);
        default:
            return SafetyCheck.fail(ABO, "bloodbank.check.abo.incompatible", donorText, patientText);
        }
    }

    /** Donor red cells must carry no antigen the patient's plasma attacks. */
    static boolean redCellsSuit(Abo donor, Abo patient) {
        if (donor == Abo.O) {
            return true;
        }
        if (patient == null) {
            return false;
        }
        return switch (donor) {
        case A -> patient == Abo.A || patient == Abo.AB;
        case B -> patient == Abo.B || patient == Abo.AB;
        case AB -> patient == Abo.AB;
        default -> false;
        };
    }

    /** Donor plasma must carry no antibody against the patient's red cells. */
    static boolean plasmaSuits(Abo donor, Abo patient) {
        if (donor == Abo.AB) {
            return true;
        }
        if (patient == null) {
            return false;
        }
        return switch (donor) {
        case A -> patient == Abo.A || patient == Abo.O;
        case B -> patient == Abo.B || patient == Abo.O;
        case O -> patient == Abo.O;
        default -> false;
        };
    }

    static SafetyCheck rhd(ComponentClass component, RhD donor, RhD patient, BloodBankRules rules) {
        String donorText = donor == null ? "" : donor.name();
        String patientText = patient == null ? "" : patient.name();
        if (component == ComponentClass.PLASMA || component == ComponentClass.CRYO) {
            return SafetyCheck.pass(RHD, "bloodbank.check.rhd.notApplicable");
        }
        if (donor == null) {
            return SafetyCheck.fail(RHD, "bloodbank.check.rhd.unitUnknown");
        }
        // an untyped or unconfirmed patient is treated as RhD negative
        boolean patientMayBeNegative = patient != RhD.POS;
        if (donor == RhD.NEG || !patientMayBeNegative) {
            return SafetyCheck.pass(RHD, "bloodbank.check.rhd.ok", donorText, patientText);
        }
        if (component == ComponentClass.PLATELETS) {
            RuleAction action = rules.rhdNegPatientRhdPosPlatelets();
            if (action == RuleAction.ALLOW) {
                return SafetyCheck.pass(RHD, "bloodbank.check.rhd.ok", donorText, patientText);
            }
            if (action == RuleAction.WARN) {
                return SafetyCheck.warn(RHD, "bloodbank.check.rhd.positivePlateletsForNegative", donorText,
                        patientText);
            }
            return SafetyCheck.fail(RHD, "bloodbank.check.rhd.positivePlateletsForNegative", donorText, patientText);
        }
        return SafetyCheck.fail(RHD,
                patient == null ? "bloodbank.check.rhd.patientUnknown" : "bloodbank.check.rhd.positiveForNegative",
                donorText, patientText);
    }
}
