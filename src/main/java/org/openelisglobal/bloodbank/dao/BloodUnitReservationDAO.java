package org.openelisglobal.bloodbank.dao;

import java.util.List;
import java.util.Optional;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.ReservationStatus;
import org.openelisglobal.bloodbank.valueholder.BloodUnitReservation;
import org.openelisglobal.common.dao.BaseDAO;

public interface BloodUnitReservationDAO extends BaseDAO<BloodUnitReservation, Long> {

    /** The unit's reservation in this status, if any (at most one is ACTIVE). */
    Optional<BloodUnitReservation> getForUnit(Long unitId, ReservationStatus status);

    /**
     * Live (ACTIVE or ISSUED) reservations that use any of these crossmatch
     * results, with their units.
     */
    List<BloodUnitReservation> getLiveByCrossmatchResultIds(List<Long> crossmatchResultIds);
}
