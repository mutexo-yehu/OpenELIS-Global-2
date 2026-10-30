package org.openelisglobal.analyzer.dao;

import java.util.List;
import java.util.Optional;
import org.hibernate.Session;
import org.openelisglobal.analyzer.valueholder.AnalyzerOrderDispatch;
import org.openelisglobal.analyzer.valueholder.AnalyzerOrderDispatch.Status;
import org.openelisglobal.common.daoimpl.BaseDAOImpl;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
public class AnalyzerOrderDispatchDAOImpl extends BaseDAOImpl<AnalyzerOrderDispatch, String>
        implements AnalyzerOrderDispatchDAO {

    public AnalyzerOrderDispatchDAOImpl() {
        super(AnalyzerOrderDispatch.class);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AnalyzerOrderDispatch> findBySampleAndAnalyzer(String sampleId, String analyzerId) {
        return entityManager.unwrap(Session.class)
                .createQuery("FROM AnalyzerOrderDispatch d WHERE d.sampleId = :sampleId AND d.analyzerId = :analyzerId",
                        AnalyzerOrderDispatch.class)
                .setParameter("sampleId", sampleId).setParameter("analyzerId", analyzerId).setMaxResults(1)
                .getResultList().stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AnalyzerOrderDispatch> findSendable(int maxAttempts, int limit) {
        return entityManager.unwrap(Session.class)
                .createQuery(
                        "FROM AnalyzerOrderDispatch d WHERE d.status = :pending"
                                + " OR (d.status = :failed AND d.attempts < :maxAttempts) ORDER BY d.createdAt, d.id",
                        AnalyzerOrderDispatch.class)
                .setParameter("pending", Status.PENDING).setParameter("failed", Status.FAILED)
                .setParameter("maxAttempts", maxAttempts).setMaxResults(limit).getResultList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AnalyzerOrderDispatch> findRecent(int limit) {
        return entityManager.unwrap(Session.class)
                .createQuery("FROM AnalyzerOrderDispatch d ORDER BY d.createdAt DESC, d.id DESC",
                        AnalyzerOrderDispatch.class)
                .setMaxResults(limit).getResultList();
    }
}
