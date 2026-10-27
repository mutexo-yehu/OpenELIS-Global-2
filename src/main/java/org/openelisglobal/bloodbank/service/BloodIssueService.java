package org.openelisglobal.bloodbank.service;

import java.util.List;
import org.openelisglobal.bloodbank.form.BloodBankForms.EmergencyIssueCheckRequest;
import org.openelisglobal.bloodbank.form.BloodBankForms.EmergencyIssueRequest;
import org.openelisglobal.bloodbank.form.BloodBankForms.IssueRequest;
import org.openelisglobal.bloodbank.form.BloodBankForms.RetrospectiveCheckRequest;
import org.openelisglobal.bloodbank.form.BloodBankViews.CandidateUnit;
import org.openelisglobal.bloodbank.form.BloodBankViews.EmergencyIssueView;
import org.openelisglobal.bloodbank.form.BloodBankViews.EmergencyIssues;
import org.openelisglobal.bloodbank.form.BloodBankViews.IssueCheck;
import org.openelisglobal.bloodbank.form.BloodBankViews.ReservationView;
import org.openelisglobal.bloodbank.form.BloodBankViews.UnitActionResult;

/** From the shelf to the patient: reserve, issue, return, transfused. */
public interface BloodIssueService {

    /**
     * Available units (of one product type, if given) with their verdict for this
     * patient: group confirmation, ABO, RhD and expiry.
     */
    List<CandidateUnit> candidates(Long patientId, Long productTypeId);

    /**
     * Holds an available, unexpired, compatible unit for a patient. Red cells and
     * whole blood need the patient's validated, compatible, unexpired crossmatch
     * result, which then covers this unit only.
     */
    ReservationView reserve(Long unitId, Long patientId, Long crossmatchResultId, String sysUserId);

    /**
     * ACTIVE reservation → RELEASED, unit back to AVAILABLE. A reason is required.
     */
    ReservationView unreserve(Long reservationId, String reason, String sysUserId);

    /**
     * The issue checklist as it stands, with the second person still to sign.
     * Writes nothing.
     */
    IssueCheck check(Long unitId, String patientIdentifier, String scannedDonationNumber);

    /**
     * Issues the unit: re-authenticates the second person, then repeats every check
     * inside this transaction with the unit locked, and refuses (UNSAFE, with the
     * checklist) if any fails, or if a check warned and no reason was given.
     */
    UnitActionResult issue(Long unitId, IssueRequest request, String sysUserId);

    /**
     * An issued unit comes back: within the return window it goes back to stock,
     * later it is discarded. Both outcomes are in the trail.
     */
    UnitActionResult returnUnit(Long unitId, String reason, String sysUserId);

    /**
     * An issued unit was transfused; an optional reaction note is its own trail
     * entry.
     */
    UnitActionResult transfused(Long unitId, String reactionNote, String sysUserId);

    /**
     * The emergency issue checklist as it stands, with the second person still to
     * sign. Writes nothing.
     */
    IssueCheck emergencyCheck(Long unitId, EmergencyIssueCheckRequest request);

    /**
     * Issues group O red cells or group AB plasma to a patient whose group and
     * crossmatch are not done: refused when the site has turned emergency issue
     * off, or a field is missing, or any emergency check fails. The normal issue is
     * not changed by this path. The issue is marked EMERGENCY in the trail and
     * leaves a retrospective check pending.
     */
    UnitActionResult emergencyIssue(Long unitId, EmergencyIssueRequest request, String sysUserId);

    /** The patient's emergency issues and the site's setting. */
    EmergencyIssues emergencyIssues(Long patientId);

    /**
     * Records the lab's retrospective group and crossmatch of one emergency unit,
     * once; INCOMPATIBLE stays as a warning on the patient and in the trail.
     */
    EmergencyIssueView retrospectiveCheck(Long reservationId, RetrospectiveCheckRequest request, String sysUserId);
}
