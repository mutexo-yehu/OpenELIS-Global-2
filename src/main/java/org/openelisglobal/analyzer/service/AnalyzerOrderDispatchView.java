package org.openelisglobal.analyzer.service;

import java.util.List;

/**
 * One worklist row as the Order worklist screen shows it. Times are ISO-8601.
 */
public record AnalyzerOrderDispatchView(String id, String accessionNumber, String analyzerId, String analyzerName,
        String tests, List<String> loincCodes, String status, int attempts, String lastError, String createdAt,
        String sentAt, String lastUpdated) {
}
