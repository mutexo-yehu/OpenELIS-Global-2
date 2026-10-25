package org.openelisglobal.bloodbank.daoimpl;

import java.util.List;
import java.util.Optional;
import org.hibernate.Session;
import org.openelisglobal.bloodbank.dao.BloodProductCodeDAO;
import org.openelisglobal.bloodbank.valueholder.BloodProductCode;
import org.openelisglobal.common.daoimpl.BaseDAOImpl;
import org.openelisglobal.common.exception.LIMSRuntimeException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
public class BloodProductCodeDAOImpl extends BaseDAOImpl<BloodProductCode, Long> implements BloodProductCodeDAO {

    public BloodProductCodeDAOImpl() {
        super(BloodProductCode.class);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BloodProductCode> getByPrefix(String prefix) {
        try {
            return entityManager.unwrap(Session.class)
                    .createQuery("FROM BloodProductCode c JOIN FETCH c.productType WHERE c.isbtCodePrefix = :prefix",
                            BloodProductCode.class)
                    .setParameter("prefix", prefix).uniqueResultOptional();
        } catch (RuntimeException e) {
            throw new LIMSRuntimeException("Error reading blood product code", e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<BloodProductCode> getActiveByPrefixes(List<String> prefixes) {
        if (prefixes == null || prefixes.isEmpty()) {
            return List.of();
        }
        try {
            return entityManager.unwrap(Session.class)
                    .createQuery(
                            "FROM BloodProductCode c JOIN FETCH c.productType t"
                                    + " WHERE c.active = true AND c.isbtCodePrefix IN (:prefixes)",
                            BloodProductCode.class)
                    .setParameterList("prefixes", prefixes).list();
        } catch (RuntimeException e) {
            throw new LIMSRuntimeException("Error reading blood product codes", e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<BloodProductCode> getAllWithTypes() {
        try {
            return entityManager.unwrap(Session.class)
                    .createQuery("FROM BloodProductCode c JOIN FETCH c.productType ORDER BY c.isbtCodePrefix",
                            BloodProductCode.class)
                    .list();
        } catch (RuntimeException e) {
            throw new LIMSRuntimeException("Error listing blood product codes", e);
        }
    }
}
