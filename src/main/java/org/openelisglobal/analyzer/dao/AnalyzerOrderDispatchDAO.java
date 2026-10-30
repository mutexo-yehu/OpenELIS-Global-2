package org.openelisglobal.analyzer.dao;

import java.util.List;
import java.util.Optional;
import org.openelisglobal.analyzer.valueholder.AnalyzerOrderDispatch;
import org.openelisglobal.common.dao.BaseDAO;

public interface AnalyzerOrderDispatchDAO extends BaseDAO<AnalyzerOrderDispatch, String> {

    Optional<AnalyzerOrderDispatch> findBySampleAndAnalyzer(String sampleId, String analyzerId);

    /**
     * PENDING rows, and FAILED rows with fewer than {@code maxAttempts}, oldest
     * first.
     */
    List<AnalyzerOrderDispatch> findSendable(int maxAttempts, int limit);

    /** Newest first. */
    List<AnalyzerOrderDispatch> findRecent(int limit);
}
