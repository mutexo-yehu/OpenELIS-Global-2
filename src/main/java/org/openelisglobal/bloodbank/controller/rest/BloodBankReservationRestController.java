package org.openelisglobal.bloodbank.controller.rest;

import jakarta.servlet.http.HttpServletRequest;
import org.openelisglobal.bloodbank.form.BloodBankForms.ReasonRequest;
import org.openelisglobal.bloodbank.form.BloodBankForms.ReserveRequest;
import org.openelisglobal.bloodbank.form.BloodBankForms.RetrospectiveCheckRequest;
import org.openelisglobal.bloodbank.form.BloodBankViews.EmergencyIssueView;
import org.openelisglobal.bloodbank.form.BloodBankViews.ReservationView;
import org.openelisglobal.bloodbank.service.BloodIssueService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Holding a unit for a patient, and letting it go. */
@RestController
@RequestMapping(value = "/rest/bloodbank/reservations", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize(BloodBankGuards.BENCH)
public class BloodBankReservationRestController extends BloodBankRestSupport {

    @Autowired
    private BloodIssueService issueService;

    @PostMapping
    @PreAuthorize(BloodBankGuards.BENCH)
    public ResponseEntity<ReservationView> reserve(@RequestBody(required = false) ReserveRequest body,
            HttpServletRequest request) {
        ReserveRequest form = body == null ? new ReserveRequest(null, null, null) : body;
        return ResponseEntity.status(HttpStatus.CREATED).body(issueService.reserve(form.unitId(), form.patientId(),
                form.crossmatchResultId(), getSysUserId(request)));
    }

    @PostMapping("/{id}/release")
    @PreAuthorize(BloodBankGuards.BENCH)
    public ReservationView release(@PathVariable("id") Long id, @RequestBody(required = false) ReasonRequest body,
            HttpServletRequest request) {
        return issueService.unreserve(id, body == null ? null : body.reason(), getSysUserId(request));
    }

    /** The lab's retrospective group and crossmatch of an emergency unit. */
    @PostMapping("/{id}/retrospective-check")
    @PreAuthorize(BloodBankGuards.LAB_CHECK)
    public EmergencyIssueView retrospectiveCheck(@PathVariable("id") Long id,
            @RequestBody(required = false) RetrospectiveCheckRequest body, HttpServletRequest request) {
        return issueService.retrospectiveCheck(id, body, getSysUserId(request));
    }
}
