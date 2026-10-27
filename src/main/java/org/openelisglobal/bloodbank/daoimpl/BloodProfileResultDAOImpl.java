package org.openelisglobal.bloodbank.daoimpl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import org.hibernate.Session;
import org.openelisglobal.bloodbank.dao.BloodProfileResultDAO;
import org.openelisglobal.common.exception.LIMSRuntimeException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(readOnly = true)
public class BloodProfileResultDAOImpl implements BloodProfileResultDAO {

    /**
     * One query: result → analysis → test, sample item → sample, and the sample's
     * patient through SampleHuman (which holds plain ids, hence the theta join).
     * The order form records the collection time on the sample item; older paths on
     * the sample.
     */
    private static final String RESULTS_HQL = "SELECT r.id, r.value, r.resultType, t.localCode, s.id,"
            + " s.accessionNumber, COALESCE(si.collectionDate, s.collectionDate)"
            + " FROM Result r JOIN r.analysis a JOIN a.test t JOIN a.sampleItem si JOIN si.sample s, SampleHuman sh"
            + " WHERE sh.sampleId = s.id AND sh.patientId = :patientId AND a.statusId = :statusId"
            + " AND LOWER(t.localCode) IN (:codes)"
            + " ORDER BY COALESCE(si.collectionDate, s.collectionDate) DESC, r.id DESC";

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<ProfileResultRow> getResults(String patientId, List<String> testLocalCodes, String analysisStatusId) {
        if (patientId == null || testLocalCodes == null || testLocalCodes.isEmpty() || analysisStatusId == null) {
            return List.of();
        }
        List<String> codes = testLocalCodes.stream().map(c -> c.trim().toLowerCase(Locale.ROOT)).toList();
        try {
            List<Object[]> rows = entityManager.unwrap(Session.class).createQuery(RESULTS_HQL, Object[].class)
                    .setParameter("patientId", patientId).setParameter("statusId", analysisStatusId)
                    .setParameterList("codes", codes).list();
            return rows.stream().map(row -> new ProfileResultRow(asString(row[0]), asString(row[1]), asString(row[2]),
                    asString(row[3]), asString(row[4]), asString(row[5]), (Timestamp) row[6])).toList();
        } catch (RuntimeException e) {
            throw new LIMSRuntimeException("Error reading the patient's blood bank results", e);
        }
    }

    @Override
    public String getPatientIdForAccession(String accessionNumber) {
        if (accessionNumber == null || accessionNumber.isBlank()) {
            return null;
        }
        try {
            List<Object> ids = entityManager.unwrap(Session.class)
                    .createQuery("SELECT sh.patientId FROM SampleHuman sh, Sample s"
                            + " WHERE sh.sampleId = s.id AND s.accessionNumber = :accession", Object.class)
                    .setParameter("accession", accessionNumber.trim()).setMaxResults(1).list();
            return ids.isEmpty() ? null : asString(ids.get(0));
        } catch (RuntimeException e) {
            throw new LIMSRuntimeException("Error resolving a lab number to its patient", e);
        }
    }

    @Override
    public String getAccessionForResult(String resultId) {
        if (resultId == null || resultId.isBlank()) {
            return null;
        }
        try {
            List<String> accessions = entityManager.unwrap(Session.class)
                    .createQuery("SELECT s.accessionNumber FROM Result r JOIN r.analysis a JOIN a.sampleItem si"
                            + " JOIN si.sample s WHERE r.id = :resultId", String.class)
                    .setParameter("resultId", resultId.trim()).setMaxResults(1).list();
            return accessions.isEmpty() ? null : accessions.get(0);
        } catch (RuntimeException e) {
            throw new LIMSRuntimeException("Error reading the lab number of a result", e);
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
