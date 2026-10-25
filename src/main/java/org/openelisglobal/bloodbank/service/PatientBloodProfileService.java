package org.openelisglobal.bloodbank.service;

import org.openelisglobal.bloodbank.safety.PatientBloodProfile;

/**
 * Reads a patient's validated blood bank results. Never writes OpenELIS
 * results.
 */
public interface PatientBloodProfileService {

    /**
     * @throws BloodBankException NOT_FOUND when there is no such patient
     */
    PatientBloodProfile getProfile(Long patientId);

    /**
     * The patient a scanned or typed identifier belongs to: a lab number
     * (accession) first, then the national ID, then the external ID.
     *
     * @return the patient's id, or null when it matches nobody
     */
    Long resolvePatientIdentifier(String identifier);
}
