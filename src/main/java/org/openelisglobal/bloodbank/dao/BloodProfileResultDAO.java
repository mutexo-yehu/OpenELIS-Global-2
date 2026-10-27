package org.openelisglobal.bloodbank.dao;

import java.sql.Timestamp;
import java.util.List;

/**
 * Reads, never writes, OpenELIS results for the patient blood profile.
 */
public interface BloodProfileResultDAO {

    /**
     * One validated result of a configured test.
     *
     * @param resultType the result's type: D, M and C values are dictionary ids
     */
    record ProfileResultRow(String resultId, String value, String resultType, String testLocalCode, String sampleId,
            String accessionNumber, Timestamp collectionDate) {
    }

    /**
     * The patient's results, on analyses in the given status, of the tests whose
     * catalogue (local) codes are given (compared case-insensitively).
     */
    List<ProfileResultRow> getResults(String patientId, List<String> testLocalCodes, String analysisStatusId);

    /** The patient that the sample with this lab number belongs to, or null. */
    String getPatientIdForAccession(String accessionNumber);

    /** The lab number of the sample that a result belongs to, or null. */
    String getAccessionForResult(String resultId);
}
