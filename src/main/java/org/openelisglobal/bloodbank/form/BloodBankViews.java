package org.openelisglobal.bloodbank.form;

import java.util.List;
import org.openelisglobal.bloodbank.safety.SafetyCheck;
import org.openelisglobal.bloodbank.safety.SecondCheckMode;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.Abo;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.ComponentClass;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.EventType;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.ReservationStatus;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.RetrospectiveResult;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.RhD;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.UnitStatus;

/**
 * What the blood bank REST endpoints answer. Built inside the service
 * transaction, so nothing here is lazy. Times are ISO-8601 instants (UTC,
 * {@code 2026-11-02T03:59:00Z}); null fields are left out of the JSON.
 */
public final class BloodBankViews {

    private BloodBankViews() {
    }

    public record ProductTypeView(Long id, String code, String name, ComponentClass componentClass,
            boolean requiresCrossmatch, boolean active) {
    }

    public record ProductCodeView(Long id, String isbtCodePrefix, String description, boolean active,
            ProductTypeView productType) {
    }

    /**
     * @param expired             expiry has passed
     * @param expiringSoon        expires within the configured warning hours, not
     *                            yet expired
     * @param issuedAt            when an ISSUED unit was issued; null otherwise
     * @param returnWindowMinutes the return window for an ISSUED unit, so the
     *                            screen can say whether a return goes back to
     *                            stock; null otherwise. The return itself decides.
     * @param emergency           true for an ISSUED unit that went out as an
     *                            emergency issue; null otherwise
     */
    public record UnitView(Long id, String fhirUuid, String donationNumber, String productCode,
            ProductTypeView productType, Abo abo, RhD rhd, String expiresAt, String collectedAt, String supplier,
            Integer volumeMl, UnitStatus status, String receivedAt, Integer receivedBy, Long patientId, boolean expired,
            boolean expiringSoon, String issuedAt, Integer returnWindowMinutes, Boolean emergency) {

        public UnitView(Long id, String fhirUuid, String donationNumber, String productCode,
                ProductTypeView productType, Abo abo, RhD rhd, String expiresAt, String collectedAt, String supplier,
                Integer volumeMl, UnitStatus status, String receivedAt, Integer receivedBy, Long patientId,
                boolean expired, boolean expiringSoon) {
            this(id, fhirUuid, donationNumber, productCode, productType, abo, rhd, expiresAt, collectedAt, supplier,
                    volumeMl, status, receivedAt, receivedBy, patientId, expired, expiringSoon, null, null, null);
        }

        /** The same unit, with when it was issued and the return window. */
        public UnitView withIssue(String issued, Integer windowMinutes) {
            return withIssue(issued, windowMinutes, false);
        }

        /**
         * The same unit, with when it was issued, the return window, and whether it was
         * an emergency issue.
         */
        public UnitView withIssue(String issued, Integer windowMinutes, boolean emergencyIssue) {
            return new UnitView(id, fhirUuid, donationNumber, productCode, productType, abo, rhd, expiresAt,
                    collectedAt, supplier, volumeMl, status, receivedAt, receivedBy, patientId, expired, expiringSoon,
                    issued, windowMinutes, emergencyIssue ? Boolean.TRUE : null);
        }
    }

    /**
     * One emergency issue to a patient and where its retrospective check stands.
     *
     * @param unitStatus              the unit's status now (issued, transfused,
     *                                back in stock...)
     * @param rhdPositiveAcknowledged O RhD positive red cells were given with the
     *                                acknowledgement
     * @param retrospectiveResult     null while the check is pending
     */
    public record EmergencyIssueView(Long reservationId, Long unitId, String donationNumber, String productCode,
            ProductTypeView productType, Abo abo, RhD rhd, UnitStatus unitStatus, String issuedAt, Integer issuedBy,
            String issuedByName, String requestingClinician, String clinicalReason, boolean rhdPositiveAcknowledged,
            RetrospectiveResult retrospectiveResult, String retrospectiveNote, Integer retrospectiveCheckedBy,
            String retrospectiveCheckedByName, String retrospectiveCheckedAt) {
    }

    /**
     * A patient's emergency issues, and whether a new one may be made.
     *
     * @param enabled         the site's emergency issue setting
     * @param secondCheckMode the site's second-check setting, so the screen asks
     *                        for what the server will require
     */
    public record EmergencyIssues(boolean enabled, SecondCheckMode secondCheckMode, List<EmergencyIssueView> issues) {
    }

    public record ReservationView(Long id, Long unitId, Long patientId, Long crossmatchResultId,
            String crossmatchValidUntil, ReservationStatus status, Integer reservedBy, String reservedAt,
            String issuedAt, String closedAt) {
    }

    /** One trail entry, with the names of the people involved. */
    public record EventView(Long id, Long unitId, String donationNumber, String productCode, EventType eventType,
            Long patientId, Long reservationId, Integer performedBy, String performedByName, Integer secondCheckerId,
            String secondCheckerName, String occurredAt, String reason, String location, String collectorName,
            String details, String patientName, String patientNationalId, String labNumber) {

        public EventView(Long id, Long unitId, String donationNumber, String productCode, EventType eventType,
                Long patientId, Long reservationId, Integer performedBy, String performedByName,
                Integer secondCheckerId, String secondCheckerName, String occurredAt, String reason, String location,
                String collectorName, String details) {
            this(id, unitId, donationNumber, productCode, eventType, patientId, reservationId, performedBy,
                    performedByName, secondCheckerId, secondCheckerName, occurredAt, reason, location, collectorName,
                    details, null, null, null);
        }

        /** The same entry, naming its patient and the lab number of its crossmatch. */
        public EventView withPatient(String name, String nationalId, String lab) {
            return new EventView(id, unitId, donationNumber, productCode, eventType, patientId, reservationId,
                    performedBy, performedByName, secondCheckerId, secondCheckerName, occurredAt, reason, location,
                    collectorName, details, name, nationalId, lab);
        }
    }

    /** A unit with its live reservation (if any) and its whole trail. */
    public record UnitDetail(UnitView unit, ReservationView activeReservation, List<EventView> trail) {
    }

    public record SummaryRow(ProductTypeView productType, Abo abo, RhD rhd, long count) {
    }

    /**
     * Stock board: counts of AVAILABLE and RESERVED units by product and group, and
     * how many of them are expiring or expired.
     */
    public record StockSummary(List<SummaryRow> rows, long expiringSoon, long expired, int expiryWarnHours) {
    }

    /** One problem with a received line or a scan. */
    public record LineError(String code, List<String> args) {
    }

    /** What a set of scans says, before anything is saved. */
    public record ParsedLabel(String donationNumber, String flags, Abo abo, RhD rhd, String productCode,
            ProductTypeView productType, String expiresAt, List<LineError> errors) {
    }

    /** The outcome of one line of a delivery. */
    public record ReceiveLineResult(int index, boolean received, Long unitId, String donationNumber, String productCode,
            List<LineError> errors) {
    }

    /**
     * An available unit and whether it suits the patient.
     *
     * @param selectable it may be reserved for this patient (nothing failed;
     *                   warnings allowed)
     * @param checks     group confirmation, ABO, RhD and expiry, each with its
     *                   reason
     */
    public record CandidateUnit(UnitView unit, boolean selectable, List<SafetyCheck> checks) {
    }

    /**
     * The issue checklist.
     *
     * @param allowed               nothing failed and nothing is pending
     * @param warningReasonRequired a check passed with a warning, so issue needs a
     *                              reason
     * @param secondCheckMode       the site's second-check setting, so the screen
     *                              asks for what the server will require
     */
    public record IssueCheck(Long unitId, Long scannedPatientId, boolean allowed, boolean warningReasonRequired,
            List<SafetyCheck> checks, SecondCheckMode secondCheckMode) {

        public IssueCheck(Long unitId, Long scannedPatientId, boolean allowed, boolean warningReasonRequired,
                List<SafetyCheck> checks) {
            this(unitId, scannedPatientId, allowed, warningReasonRequired, checks, SecondCheckMode.REQUIRED);
        }
    }

    /**
     * A unit's state after issue, return or transfusion, and the entries just
     * added.
     */
    public record UnitActionResult(UnitView unit, List<EventView> events) {
    }
}
