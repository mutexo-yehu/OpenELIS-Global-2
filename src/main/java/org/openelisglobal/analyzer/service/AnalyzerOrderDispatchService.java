package org.openelisglobal.analyzer.service;

import java.time.Instant;
import java.util.List;

/**
 * The analyser order worklist: orders queued for analysers that take orders,
 * sent through the Analyzer Bridge.
 */
public interface AnalyzerOrderDispatchService {

    int MAX_ATTEMPTS = 5;

    /** Outcome of queueing one sample. */
    enum EnqueueOutcome {
        QUEUED, NOTHING_TO_SEND, DISABLED, NOT_FOUND
    }

    record EnqueueResult(EnqueueOutcome outcome, int rows) {
    }

    /**
     * Queues the sample's tests that are mapped to an active analyser taking
     * orders, one row per analyser. A row that already exists is left alone.
     */
    EnqueueResult enqueueSample(String sampleId);

    /**
     * Queues an existing order by hand. A row that already exists for that analyser
     * is set back to PENDING, so the order is sent again.
     */
    EnqueueResult enqueueAccession(String accessionNumber, String sysUserId);

    /** Ids of the rows whose turn it is, given the retry backoff. */
    List<String> dueIds(Instant now, int limit);

    /** Sends one row to the Bridge and records the outcome. */
    void send(String dispatchId);

    /**
     * Sets a FAILED row back to PENDING.
     *
     * @return false if there is no such row, or it is not FAILED
     */
    boolean retry(String dispatchId, String sysUserId);

    List<AnalyzerOrderDispatchView> recent(int limit);
}
