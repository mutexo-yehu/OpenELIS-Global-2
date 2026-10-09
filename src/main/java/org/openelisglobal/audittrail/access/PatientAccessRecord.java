package org.openelisglobal.audittrail.access;

import java.sql.Timestamp;

/**
 * One successful read of patient-identifiable data, as written to
 * {@code clinlims.patient_access_log}.
 *
 * @param patientId       the patient's id when the request named one, else null
 * @param accessionNumber the lab number when the request named one, else null
 */
public record PatientAccessRecord(Timestamp accessTime, Integer sysUserId, String loginName, String httpMethod,
        String resource, String queryString, Integer patientId, String accessionNumber, String clientAddress,
        int httpStatus) {
}
