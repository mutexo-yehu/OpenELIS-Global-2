package org.openelisglobal.analyzer.service;

import java.time.Instant;
import java.util.List;
import org.openelisglobal.common.log.LogEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every 15 s, sends the worklist rows whose turn it is (PENDING, or FAILED and
 * due under the retry backoff) to the Analyzer Bridge. Does nothing while
 * {@code analyzer.worklist.enabled} is false.
 */
@Component
public class AnalyzerOrderSender {

    private static final int BATCH = 20;

    private final AnalyzerOrderDispatchService dispatchService;
    private final AnalyzerWorklistSettings settings;

    public AnalyzerOrderSender(AnalyzerOrderDispatchService dispatchService, AnalyzerWorklistSettings settings) {
        this.dispatchService = dispatchService;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${analyzer.worklist.poll-ms:15000}", initialDelayString = "${analyzer.worklist.initial-delay-ms:30000}")
    public synchronized void tick() {
        List<String> due;
        try {
            if (!settings.isEnabled()) {
                return;
            }
            due = dispatchService.dueIds(Instant.now(), BATCH);
        } catch (RuntimeException e) {
            LogEvent.logError(getClass().getSimpleName(), "tick", "could not read the worklist: " + e.getMessage());
            return;
        }
        for (String id : due) {
            try {
                dispatchService.send(id);
            } catch (RuntimeException e) {
                LogEvent.logError(getClass().getSimpleName(), "tick",
                        "could not send worklist row " + id + ": " + e.getMessage());
            }
        }
    }
}
