package org.openelisglobal.bloodbank.service;

import java.util.List;
import org.openelisglobal.bloodbank.form.BloodBankViews.EventView;

/** Look-back: the trail by unit, by donation number and by patient. */
public interface BloodTraceService {

    List<EventView> forUnit(Long unitId);

    /** Every component of a donation (all product codes of one DIN). */
    List<EventView> forDonationNumber(String donationNumber);

    List<EventView> forPatient(Long patientId);

    /** The same entries as CSV, one line per entry, with a header line. */
    String toCsv(List<EventView> events);
}
