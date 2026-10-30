package org.openelisglobal.analyzer.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.openelisglobal.analyzer.service.AnalyzerOrderDispatchService;
import org.openelisglobal.analyzer.service.AnalyzerOrderDispatchService.EnqueueResult;
import org.openelisglobal.analyzer.service.AnalyzerOrderDispatchView;
import org.openelisglobal.analyzer.service.AnalyzerService;
import org.openelisglobal.analyzer.service.AnalyzerWorklistSettings;
import org.openelisglobal.analyzer.valueholder.Analyzer;
import org.openelisglobal.common.rest.BaseRestController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Orders queued for and sent to analysers through the Analyzer Bridge. */
@RestController
@RequestMapping("/rest/analyzer/worklist")
@PreAuthorize("hasAnyRole('ADMIN', 'ANALYSER_IMPORT')")
public class AnalyzerWorklistRestController extends BaseRestController {

    private static final int LIST_LIMIT = 200;

    private final AnalyzerOrderDispatchService dispatchService;
    private final AnalyzerWorklistSettings settings;
    private final AnalyzerService analyzerService;

    public AnalyzerWorklistRestController(AnalyzerOrderDispatchService dispatchService,
            AnalyzerWorklistSettings settings, AnalyzerService analyzerService) {
        this.dispatchService = dispatchService;
        this.settings = settings;
        this.analyzerService = analyzerService;
    }

    /**
     * The active analysers a technician can open results for: id and name only,
     * nothing about how they are set up.
     */
    @GetMapping("/analyzers")
    public ResponseEntity<Map<String, Object>> analyzers() {
        List<Map<String, String>> rows = analyzerService.getAll().stream().filter(Analyzer::isActive)
                .sorted(Comparator.comparing(Analyzer::getName, String.CASE_INSENSITIVE_ORDER))
                .map(a -> Map.of("id", a.getId(), "name", a.getName())).toList();
        return ResponseEntity.ok(Map.of("status", "success", "data", rows));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list() {
        List<AnalyzerOrderDispatchView> rows = dispatchService.recent(LIST_LIMIT);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", settings.isEnabled());
        data.put("count", rows.size());
        data.put("rows", rows);
        return ResponseEntity.ok(Map.of("status", "success", "data", data));
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<Map<String, Object>> retry(@PathVariable String id, HttpServletRequest request) {
        if (!dispatchService.retry(id, getSysUserId(request))) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("status", "error", "messageKey", "analyzer.worklist.error.notRetryable"));
        }
        return ResponseEntity.ok(Map.of("status", "success", "id", id));
    }

    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> send(@RequestParam String accessionNumber, HttpServletRequest request) {
        EnqueueResult result = dispatchService.enqueueAccession(accessionNumber, getSysUserId(request));
        return switch (result.outcome()) {
        case QUEUED -> ResponseEntity.ok(Map.of("status", "success", "queued", result.rows()));
        case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("status", "error", "messageKey", "analyzer.worklist.error.orderNotFound"));
        case DISABLED -> ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("status", "error", "messageKey", "analyzer.worklist.error.disabled"));
        case NOTHING_TO_SEND -> ResponseEntity.unprocessableEntity()
                .body(Map.of("status", "error", "messageKey", "analyzer.worklist.error.nothingToSend"));
        };
    }
}
