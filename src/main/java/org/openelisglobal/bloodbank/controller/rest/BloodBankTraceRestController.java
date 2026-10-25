package org.openelisglobal.bloodbank.controller.rest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.openelisglobal.bloodbank.form.BloodBankViews.EventView;
import org.openelisglobal.bloodbank.service.BloodBankException;
import org.openelisglobal.bloodbank.service.BloodBankException.Kind;
import org.openelisglobal.bloodbank.service.BloodTraceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Look-back by donation number, unit or patient, on screen or as CSV. */
@RestController
@RequestMapping("/rest/bloodbank/trace")
@PreAuthorize(BloodBankGuards.READ)
public class BloodBankTraceRestController extends BloodBankRestSupport {

    @Autowired
    private BloodTraceService traceService;

    /** Exactly one of {@code din}, {@code unit} or {@code patient}. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<EventView> search(@RequestParam(name = "din", required = false) String din,
            @RequestParam(name = "unit", required = false) Long unit,
            @RequestParam(name = "patient", required = false) Long patient) {
        return find(din, unit, patient);
    }

    @GetMapping("/export")
    public ResponseEntity<String> export(@RequestParam(name = "din", required = false) String din,
            @RequestParam(name = "unit", required = false) Long unit,
            @RequestParam(name = "patient", required = false) Long patient) {
        String csv = traceService.toCsv(find(din, unit, patient));
        String name = "blood-trace-" + (unit != null ? "unit-" + unit
                : patient != null ? "patient-" + patient : din.replaceAll("[^A-Za-z0-9]", "")) + ".csv";
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"").body(csv);
    }

    private List<EventView> find(String din, Long unit, Long patient) {
        int given = (din == null || din.isBlank() ? 0 : 1) + (unit == null ? 0 : 1) + (patient == null ? 0 : 1);
        if (given != 1) {
            throw new BloodBankException(Kind.INVALID, "bloodbank.trace.error.oneCriterion");
        }
        if (unit != null) {
            return traceService.forUnit(unit);
        }
        if (patient != null) {
            return traceService.forPatient(patient);
        }
        return traceService.forDonationNumber(din);
    }
}
