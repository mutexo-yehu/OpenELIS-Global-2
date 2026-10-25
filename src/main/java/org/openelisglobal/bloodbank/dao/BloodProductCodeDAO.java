package org.openelisglobal.bloodbank.dao;

import java.util.List;
import java.util.Optional;
import org.openelisglobal.bloodbank.valueholder.BloodProductCode;
import org.openelisglobal.common.dao.BaseDAO;

public interface BloodProductCodeDAO extends BaseDAO<BloodProductCode, Long> {

    Optional<BloodProductCode> getByPrefix(String prefix);

    /** Active mappings whose prefix is one of these, with their product types. */
    List<BloodProductCode> getActiveByPrefixes(List<String> prefixes);

    List<BloodProductCode> getAllWithTypes();
}
