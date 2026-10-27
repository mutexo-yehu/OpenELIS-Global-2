package org.openelisglobal.bloodbank.form;

import java.util.List;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.ComponentClass;

/**
 * What the blood bank REST endpoints accept. Times are ISO-8601 local times of
 * the laboratory ({@code 2026-11-02T14:30}) or ISBT 128 date structures where
 * stated.
 */
public final class BloodBankForms {

    private BloodBankForms() {
    }

    /** Scans of one unit's label, one data structure per entry or concatenated. */
    public record ParseRequest(List<String> scans) {
    }

    /**
     * One unit of a delivery. Scans are read first; any field typed by hand then
     * takes their place.
     *
     * @param bloodGroup typed ("O NEG", "A+"), an ISBT code ("95") or a scan
     *                   ({@code =%9500})
     * @param expiresAt  ISO date or date-time, or an ISBT {@code =>}/{@code &>}
     *                   scan
     */
    public record ReceiveLine(List<String> scans, String donationNumber, String productCode, String bloodGroup,
            String expiresAt, String collectedAt, String supplier, Integer volumeMl) {
    }

    public record ReceiveRequest(List<ReceiveLine> lines) {
    }

    public record ReasonRequest(String reason) {
    }

    /**
     * @param crossmatchResultId the validated crossmatch result covering this unit;
     *                           required for red cells and whole blood
     */
    public record ReserveRequest(Long unitId, Long patientId, Long crossmatchResultId) {
    }

    /**
     * @param patientIdentifier     the lab number (accession) or patient identifier
     *                              scanned at the bedside request form or wristband
     * @param scannedDonationNumber the donation number scanned from the bag in
     *                              hand; it must be this unit's
     */
    public record IssueCheckRequest(String patientIdentifier, String scannedDonationNumber) {
    }

    /**
     * @param location              ward or theatre the unit goes to
     * @param collectorName         who takes the unit from the lab
     * @param secondCheckerOtp      authenticator code, when the second checker's
     *                              account has one
     * @param warningReason         required when a check passed with a warning
     * @param scannedDonationNumber the donation number scanned from the bag in
     *                              hand; it must be this unit's
     */
    public record IssueRequest(String patientIdentifier, String location, String collectorName,
            String secondCheckerLogin, String secondCheckerPassword, String secondCheckerOtp, String warningReason,
            String scannedDonationNumber) {

        /** Never print the password. */
        @Override
        public String toString() {
            return "IssueRequest[patientIdentifier=" + patientIdentifier + ", scannedDonationNumber="
                    + scannedDonationNumber + ", location=" + location + ", collectorName=" + collectorName
                    + ", secondCheckerLogin=" + secondCheckerLogin + "]";
        }
    }

    /**
     * The emergency issue checklist, before anything is written.
     *
     * @param patientId               the patient on screen
     * @param patientIdentifier       scanned from the wristband or request form; it
     *                                must belong to that patient
     * @param rhdPositiveAcknowledged for O RhD positive red cells: O RhD negative
     *                                is not available, or the patient is not a
     *                                female of childbearing potential
     */
    public record EmergencyIssueCheckRequest(Long patientId, String patientIdentifier, String scannedDonationNumber,
            Boolean rhdPositiveAcknowledged) {
    }

    /**
     * An emergency issue: group O red cells or group AB plasma before the patient's
     * group and crossmatch are done.
     *
     * @param requestingClinician who asked for the blood
     * @param clinicalReason      why it cannot wait for group and crossmatch
     * @param location            ward or theatre the unit goes to
     * @param collectorName       who takes the unit from the lab
     */
    public record EmergencyIssueRequest(Long patientId, String patientIdentifier, String scannedDonationNumber,
            String location, String collectorName, String requestingClinician, String clinicalReason,
            Boolean rhdPositiveAcknowledged, String secondCheckerLogin, String secondCheckerPassword,
            String secondCheckerOtp, String warningReason) {

        /** Never print the password. */
        @Override
        public String toString() {
            return "EmergencyIssueRequest[patientId=" + patientId + ", patientIdentifier=" + patientIdentifier
                    + ", scannedDonationNumber=" + scannedDonationNumber + ", location=" + location + ", collectorName="
                    + collectorName + ", requestingClinician=" + requestingClinician + ", rhdPositiveAcknowledged="
                    + rhdPositiveAcknowledged + ", secondCheckerLogin=" + secondCheckerLogin + "]";
        }
    }

    /**
     * The lab's retrospective group and crossmatch of an emergency unit.
     *
     * @param outcome COMPATIBLE or INCOMPATIBLE
     * @param note    required: the sample and what was found
     */
    public record RetrospectiveCheckRequest(String outcome, String note) {
    }

    public record ReturnRequest(String reason) {
    }

    /**
     * @param reactionNote optional: a transfusion reaction, recorded as its own
     *                     trail entry
     */
    public record TransfusedRequest(String reactionNote) {
    }

    public record ProductTypeForm(String code, String name, ComponentClass componentClass, Boolean requiresCrossmatch,
            Boolean active) {
    }

    public record ProductCodeForm(String isbtCodePrefix, Long productTypeId, String description, Boolean active) {
    }
}
