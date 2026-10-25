package org.openelisglobal.bloodbank.daoimpl;

import java.util.Optional;
import org.hibernate.Session;
import org.openelisglobal.bloodbank.dao.BloodProductTypeDAO;
import org.openelisglobal.bloodbank.valueholder.BloodProductType;
import org.openelisglobal.common.daoimpl.BaseDAOImpl;
import org.openelisglobal.common.exception.LIMSRuntimeException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
public class BloodProductTypeDAOImpl extends BaseDAOImpl<BloodProductType, Long> implements BloodProductTypeDAO {

    public BloodProductTypeDAOImpl() {
        super(BloodProductType.class);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BloodProductType> getByCode(String code) {
        try {
            return entityManager.unwrap(Session.class)
                    .createQuery("FROM BloodProductType t WHERE t.code = :code", BloodProductType.class)
                    .setParameter("code", code).uniqueResultOptional();
        } catch (RuntimeException e) {
            throw new LIMSRuntimeException("Error reading blood product type by code", e);
        }
    }
}
