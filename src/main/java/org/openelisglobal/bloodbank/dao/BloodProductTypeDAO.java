package org.openelisglobal.bloodbank.dao;

import java.util.Optional;
import org.openelisglobal.bloodbank.valueholder.BloodProductType;
import org.openelisglobal.common.dao.BaseDAO;

public interface BloodProductTypeDAO extends BaseDAO<BloodProductType, Long> {

    Optional<BloodProductType> getByCode(String code);
}
