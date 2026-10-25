package org.openelisglobal.bloodbank.dao;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.Abo;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.RhD;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.UnitStatus;
import org.openelisglobal.bloodbank.valueholder.BloodUnit;
import org.openelisglobal.common.dao.BaseDAO;

public interface BloodUnitDAO extends BaseDAO<BloodUnit, Long> {

    Optional<BloodUnit> getByDonationNumberAndProductCode(String donationNumber, String productCode);

    List<BloodUnit> getByDonationNumber(String donationNumber);

    /**
     * The unit, locked for the rest of the transaction so two people cannot issue
     * or reserve it at once.
     */
    Optional<BloodUnit> getForUpdate(Long id);

    /**
     * Units matching every filter given; a null filter is not applied.
     *
     * @param expiringBefore only units whose expiry is before this time
     */
    List<BloodUnit> search(List<UnitStatus> statuses, Long productTypeId, Abo abo, RhD rhd, Timestamp expiringBefore);

    /**
     * Stock counts: rows of {productTypeId, abo, rhd, count} for units in these
     * statuses.
     */
    List<Object[]> countByTypeAndGroup(List<UnitStatus> statuses);

    /** How many units in these statuses expire before this time. */
    long countExpiringBefore(List<UnitStatus> statuses, Timestamp before);
}
