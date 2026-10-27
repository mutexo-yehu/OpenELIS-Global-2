package org.openelisglobal.bloodbank.controller.rest;

import java.util.List;
import org.openelisglobal.bloodbank.form.BloodBankViews.CandidateUnit;
import org.openelisglobal.bloodbank.form.BloodBankViews.EmergencyIssues;
import org.openelisglobal.bloodbank.form.BloodBankViews.EventView;
import org.openelisglobal.bloodbank.safety.PatientBloodProfile;
import org.openelisglobal.bloodbank.service.BloodIssueService;
import org.openelisglobal.bloodbank.service.BloodTraceService;
import org.openelisglobal.bloodbank.service.PatientBloodProfileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A patient, seen from the blood bank: group, screen, crossmatches, suitable
 * units, trail.
 */
@RestController
@RequestMapping(value = "/rest/bloodbank/patients", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize(BloodBankGuards.READ)
public class BloodBankPatientRestController extends BloodBankRestSupport {

    @Autowired
    private PatientBloodProfileService profileService;

    @Autowired
    private BloodIssueService issueService;

    @Autowired
    private BloodTraceService traceService;

    @GetMapping("/{id}/profile")
    public PatientBloodProfile profile(@PathVariable("id") Long id) {
        return profileService.getProfile(id);
    }

    @GetMapping("/{id}/candidate-units")
    public List<CandidateUnit> candidates(@PathVariable("id") Long id,
            @RequestParam(name = "productType", required = false) Long productType) {
        return issueService.candidates(id, productType);
    }

    /**
     * The patient's emergency issues with their retrospective checks, and whether
     * emergency issue is enabled.
     */
    @GetMapping("/{id}/emergency-issues")
    public EmergencyIssues emergencyIssues(@PathVariable("id") Long id) {
        return issueService.emergencyIssues(id);
    }

    @GetMapping("/{id}/trace")
    public List<EventView> trace(@PathVariable("id") Long id) {
        return traceService.forPatient(id);
    }
}
