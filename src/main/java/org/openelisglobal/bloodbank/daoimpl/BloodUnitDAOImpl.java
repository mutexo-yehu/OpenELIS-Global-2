package org.openelisglobal.bloodbank.daoimpl;

import jakarta.persistence.LockModeType;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.hibernate.Session;
import org.hibernate.query.Query;
import org.openelisglobal.bloodbank.dao.BloodUnitDAO;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.Abo;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.RhD;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.UnitStatus;
import org.openelisglobal.bloodbank.valueholder.BloodUnit;
import org.openelisglobal.common.daoimpl.BaseDAOImpl;
import org.openelisglobal.common.exception.LIMSRuntimeException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
public class BloodUnitDAOImpl extends BaseDAOImpl<BloodUnit, Long> implements BloodUnitDAO {

    public BloodUnitDAOImpl() {
        super(BloodUnit.class);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BloodUnit> getByDonationNumberAndProductCode(String donationNumber, String productCode) {
        try {
            return entityManager.unwrap(Session.class)
                    .createQuery(
                            "FROM BloodUnit u JOIN FETCH u.productType"
                                    + " WHERE u.donationNumber = :din AND u.productCode = :productCode",
                            BloodUnit.class)
                    .setParameter("din", donationNumber).setParameter("productCode", productCode)
                    .uniqueResultOptional();
        } catch (RuntimeException e) {
            throw new LIMSRuntimeException("Error reading blood unit by donation number", e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<BloodUnit> getByDonationNumber(String donationNumber) {
        try {
            return entityManager.unwrap(Session.class)
                    .createQuery("FROM BloodUnit u JOIN FETCH u.productType WHERE u.donationNumber = :din"
                            + " ORDER BY u.productCode", BloodUnit.class)
                    .setParameter("din", donationNumber).list();
        } catch (RuntimeException e) {
            throw new LIMSRuntimeException("Error reading blood units by donation number", e);
        }
    }

    @Override
    public Optional<BloodUnit> getForUpdate(Long id) {
        try {
            return entityManager.unwrap(Session.class)
                    .createQuery("FROM BloodUnit u JOIN FETCH u.productType WHERE u.id = :id", BloodUnit.class)
                    .setParameter("id", id).setLockMode(LockModeType.PESSIMISTIC_WRITE).uniqueResultOptional();
        } catch (RuntimeException e) {
            throw new LIMSRuntimeException("Error locking blood unit", e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<BloodUnit> search(List<UnitStatus> statuses, Long productTypeId, Abo abo, RhD rhd,
            Timestamp expiringBefore) {
        StringBuilder hql = new StringBuilder("FROM BloodUnit u JOIN FETCH u.productType t WHERE 1 = 1");
        if (statuses != null && !statuses.isEmpty()) {
            hql.append(" AND u.status IN (:statuses)");
        }
        if (productTypeId != null) {
            hql.append(" AND t.id = :productTypeId");
        }
        if (abo != null) {
            hql.append(" AND u.abo = :abo");
        }
        if (rhd != null) {
            hql.append(" AND u.rhd = :rhd");
        }
        if (expiringBefore != null) {
            hql.append(" AND u.expiresAt < :expiringBefore");
        }
        hql.append(" ORDER BY u.expiresAt, u.donationNumber, u.productCode");
        try {
            Query<BloodUnit> query = entityManager.unwrap(Session.class).createQuery(hql.toString(), BloodUnit.class);
            if (statuses != null && !statuses.isEmpty()) {
                query.setParameterList("statuses", statuses);
            }
            if (productTypeId != null) {
                query.setParameter("productTypeId", productTypeId);
            }
            if (abo != null) {
                query.setParameter("abo", abo);
            }
            if (rhd != null) {
                query.setParameter("rhd", rhd);
            }
            if (expiringBefore != null) {
                query.setParameter("expiringBefore", expiringBefore);
            }
            return query.list();
        } catch (RuntimeException e) {
            throw new LIMSRuntimeException("Error searching blood units", e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<Object[]> countByTypeAndGroup(List<UnitStatus> statuses) {
        try {
            return entityManager.unwrap(Session.class)
                    .createQuery(
                            "SELECT u.productType.id, u.abo, u.rhd, COUNT(u) FROM BloodUnit u"
                                    + " WHERE u.status IN (:statuses) GROUP BY u.productType.id, u.abo, u.rhd",
                            Object[].class)
                    .setParameterList("statuses", statuses).list();
        } catch (RuntimeException e) {
            throw new LIMSRuntimeException("Error counting blood units", e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public long countExpiringBefore(List<UnitStatus> statuses, Timestamp before) {
        try {
            Long count = entityManager.unwrap(Session.class)
                    .createQuery("SELECT COUNT(u) FROM BloodUnit u WHERE u.status IN (:statuses)"
                            + " AND u.expiresAt < :before", Long.class)
                    .setParameterList("statuses", statuses).setParameter("before", before).uniqueResult();
            return count == null ? 0 : count;
        } catch (RuntimeException e) {
            throw new LIMSRuntimeException("Error counting expiring blood units", e);
        }
    }
}
