package org.openelisglobal.bloodbank.dao;

import java.util.List;
import org.openelisglobal.bloodbank.valueholder.BloodUnitEvent;

/**
 * The unit traceability trail. Deliberately not a {@code BaseDAO}: there is no
 * update and no delete, only append and read.
 */
public interface BloodUnitEventDAO {

    Long append(BloodUnitEvent event);

    /** A unit's trail, oldest first, with the unit. */
    List<BloodUnitEvent> getByUnit(Long unitId);

    /** Every event naming this patient, oldest first, with the units. */
    List<BloodUnitEvent> getByPatient(Long patientId);

    /** Trails of all these units, oldest first, with the units. */
    List<BloodUnitEvent> getByUnits(List<Long> unitIds);
}
