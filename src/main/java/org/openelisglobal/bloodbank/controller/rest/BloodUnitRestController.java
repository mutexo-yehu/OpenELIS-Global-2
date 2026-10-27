package org.openelisglobal.bloodbank.controller.rest;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.openelisglobal.bloodbank.form.BloodBankForms.IssueCheckRequest;
import org.openelisglobal.bloodbank.form.BloodBankForms.IssueRequest;
import org.openelisglobal.bloodbank.form.BloodBankForms.ParseRequest;
import org.openelisglobal.bloodbank.form.BloodBankForms.ReasonRequest;
import org.openelisglobal.bloodbank.form.BloodBankForms.ReceiveRequest;
import org.openelisglobal.bloodbank.form.BloodBankForms.ReturnRequest;
import org.openelisglobal.bloodbank.form.BloodBankForms.TransfusedRequest;
import org.openelisglobal.bloodbank.form.BloodBankViews.EventView;
import org.openelisglobal.bloodbank.form.BloodBankViews.IssueCheck;
import org.openelisglobal.bloodbank.form.BloodBankViews.ParsedLabel;
import org.openelisglobal.bloodbank.form.BloodBankViews.ReceiveLineResult;
import org.openelisglobal.bloodbank.form.BloodBankViews.StockSummary;
import org.openelisglobal.bloodbank.form.BloodBankViews.UnitActionResult;
import org.openelisglobal.bloodbank.form.BloodBankViews.UnitDetail;
import org.openelisglobal.bloodbank.form.BloodBankViews.UnitView;
import org.openelisglobal.bloodbank.service.BloodIssueService;
import org.openelisglobal.bloodbank.service.BloodTraceService;
import org.openelisglobal.bloodbank.service.BloodUnitService;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.Abo;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.RhD;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.UnitStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Blood units: receipt, stock, stock actions and the issue desk. */
@RestController
@RequestMapping(value = "/rest/bloodbank/units", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize(BloodBankGuards.READ)
public class BloodUnitRestController extends BloodBankRestSupport {

    @Autowired
    private BloodUnitService unitService;

    @Autowired
    private BloodIssueService issueService;

    @Autowired
    private BloodTraceService traceService;

    @PostMapping("/parse")
    @PreAuthorize(BloodBankGuards.BENCH)
    public ParsedLabel parse(@RequestBody(required = false) ParseRequest body) {
        return unitService.parse(body == null ? null : body.scans());
    }

    @PostMapping("/receive")
    @PreAuthorize(BloodBankGuards.BENCH)
    public List<ReceiveLineResult> receive(@RequestBody(required = false) ReceiveRequest body,
            HttpServletRequest request) {
        return unitService.receive(body == null ? null : body.lines(), getSysUserId(request));
    }

    @GetMapping
    public List<UnitView> search(@RequestParam(name = "status", required = false) List<String> status,
            @RequestParam(name = "productType", required = false) Long productType,
            @RequestParam(name = "abo", required = false) String abo,
            @RequestParam(name = "rhd", required = false) String rhd,
            @RequestParam(name = "expiringWithinHours", required = false) Integer expiringWithinHours,
            @RequestParam(name = "patientId", required = false) Long patientId) {
        return unitService.search(enumListParam(UnitStatus.class, "status", status), productType,
                enumParam(Abo.class, "abo", abo), enumParam(RhD.class, "rhd", rhd), expiringWithinHours, patientId);
    }

    @GetMapping("/summary")
    public StockSummary summary() {
        return unitService.summary();
    }

    @GetMapping("/{id}")
    public UnitDetail get(@PathVariable("id") Long id) {
        return unitService.get(id);
    }

    @GetMapping("/{id}/trace")
    public List<EventView> trace(@PathVariable("id") Long id) {
        return traceService.forUnit(id);
    }

    @PostMapping("/{id}/quarantine")
    @PreAuthorize(BloodBankGuards.BENCH)
    public UnitActionResult quarantine(@PathVariable("id") Long id, @RequestBody(required = false) ReasonRequest body,
            HttpServletRequest request) {
        return unitService.quarantine(id, reason(body), getSysUserId(request));
    }

    @PostMapping("/{id}/release")
    @PreAuthorize(BloodBankGuards.SUPERVISOR)
    public UnitActionResult release(@PathVariable("id") Long id, @RequestBody(required = false) ReasonRequest body,
            HttpServletRequest request) {
        return unitService.release(id, reason(body), getSysUserId(request));
    }

    @PostMapping("/{id}/discard")
    @PreAuthorize(BloodBankGuards.BENCH)
    public UnitActionResult discard(@PathVariable("id") Long id, @RequestBody(required = false) ReasonRequest body,
            HttpServletRequest request) {
        return unitService.discard(id, reason(body), getSysUserId(request));
    }

    @PostMapping("/{id}/return-to-supplier")
    @PreAuthorize(BloodBankGuards.BENCH)
    public UnitActionResult returnToSupplier(@PathVariable("id") Long id,
            @RequestBody(required = false) ReasonRequest body, HttpServletRequest request) {
        return unitService.returnToSupplier(id, reason(body), getSysUserId(request));
    }

    @PostMapping("/{id}/issue/check")
    @PreAuthorize(BloodBankGuards.BENCH)
    public IssueCheck issueCheck(@PathVariable("id") Long id, @RequestBody(required = false) IssueCheckRequest body) {
        return issueService.check(id, body == null ? null : body.patientIdentifier(),
                body == null ? null : body.scannedDonationNumber());
    }

    @PostMapping("/{id}/issue")
    @PreAuthorize(BloodBankGuards.BENCH)
    public UnitActionResult issue(@PathVariable("id") Long id, @RequestBody(required = false) IssueRequest body,
            HttpServletRequest request) {
        return issueService.issue(id, body, getSysUserId(request));
    }

    @PostMapping("/{id}/return")
    @PreAuthorize(BloodBankGuards.BENCH)
    public UnitActionResult returnUnit(@PathVariable("id") Long id, @RequestBody(required = false) ReturnRequest body,
            HttpServletRequest request) {
        return issueService.returnUnit(id, body == null ? null : body.reason(), getSysUserId(request));
    }

    @PostMapping("/{id}/transfused")
    @PreAuthorize(BloodBankGuards.BENCH)
    public UnitActionResult transfused(@PathVariable("id") Long id,
            @RequestBody(required = false) TransfusedRequest body, HttpServletRequest request) {
        return issueService.transfused(id, body == null ? null : body.reactionNote(), getSysUserId(request));
    }

    private static String reason(ReasonRequest body) {
        return body == null ? null : body.reason();
    }
}
