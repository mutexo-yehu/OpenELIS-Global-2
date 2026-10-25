package org.openelisglobal.bloodbank.service;

import java.util.List;
import java.util.Optional;
import org.openelisglobal.bloodbank.form.BloodBankForms.ProductCodeForm;
import org.openelisglobal.bloodbank.form.BloodBankForms.ProductTypeForm;
import org.openelisglobal.bloodbank.form.BloodBankViews.ProductCodeView;
import org.openelisglobal.bloodbank.form.BloodBankViews.ProductTypeView;
import org.openelisglobal.bloodbank.valueholder.BloodProductType;

/** Component types and the site's mapping of ISBT 128 product codes to them. */
public interface BloodProductCatalogService {

    List<ProductTypeView> getTypes();

    ProductTypeView createType(ProductTypeForm form, String sysUserId);

    ProductTypeView updateType(Long id, ProductTypeForm form, String sysUserId);

    List<ProductCodeView> getCodes();

    ProductCodeView createCode(ProductCodeForm form, String sysUserId);

    ProductCodeView updateCode(Long id, ProductCodeForm form, String sysUserId);

    /**
     * The active type a scanned product code maps to: the mapping with the longest
     * prefix of the code wins.
     */
    Optional<BloodProductType> resolve(String productCode);

    static ProductTypeView view(BloodProductType type) {
        return type == null ? null
                : new ProductTypeView(type.getId(), type.getCode(), type.getName(), type.getComponentClass(),
                        type.isRequiresCrossmatch(), type.isActive());
    }
}
