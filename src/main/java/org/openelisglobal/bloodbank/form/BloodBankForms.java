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
     * @param patientIdentifier the lab number (accession) or patient identifier
     *                          scanned at the bedside request form or wristband
     */
    public record IssueCheckRequest(String patientIdentifier) {
    }

    /**
     * @param location         ward or theatre the unit goes to
     * @param collectorName    who takes the unit from the lab
     * @param secondCheckerOtp authenticator code, when the second checker's account
     *                         has one
     * @param warningReason    required when a check passed with a warning
     */
    public record IssueRequest(String patientIdentifier, String location, String collectorName,
            String secondCheckerLogin, String secondCheckerPassword, String secondCheckerOtp, String warningReason) {

        /** Never print the password. */
        @Override
        public String toString() {
            return "IssueRequest[patientIdentifier=" + patientIdentifier + ", location=" + location + ", collectorName="
                    + collectorName + ", secondCheckerLogin=" + secondCheckerLogin + "]";
        }
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
