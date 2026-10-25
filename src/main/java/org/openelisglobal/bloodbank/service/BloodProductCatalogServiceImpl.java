package org.openelisglobal.bloodbank.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.openelisglobal.bloodbank.dao.BloodProductCodeDAO;
import org.openelisglobal.bloodbank.dao.BloodProductTypeDAO;
import org.openelisglobal.bloodbank.form.BloodBankForms.ProductCodeForm;
import org.openelisglobal.bloodbank.form.BloodBankForms.ProductTypeForm;
import org.openelisglobal.bloodbank.form.BloodBankViews.ProductCodeView;
import org.openelisglobal.bloodbank.form.BloodBankViews.ProductTypeView;
import org.openelisglobal.bloodbank.service.BloodBankException.Kind;
import org.openelisglobal.bloodbank.valueholder.BloodProductCode;
import org.openelisglobal.bloodbank.valueholder.BloodProductType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class BloodProductCatalogServiceImpl implements BloodProductCatalogService {

    /** Shortest prefix a product code mapping may have: the E-code itself. */
    static final int MIN_PREFIX = 5;
    static final int PRODUCT_CODE_LENGTH = 8;

    @Autowired
    private BloodProductTypeDAO typeDAO;

    @Autowired
    private BloodProductCodeDAO codeDAO;

    @Override
    @Transactional(readOnly = true)
    public List<ProductTypeView> getTypes() {
        return typeDAO.getAllOrdered("name", false).stream().map(BloodProductCatalogService::view).toList();
    }

    @Override
    public ProductTypeView createType(ProductTypeForm form, String sysUserId) {
        if (form == null || isBlank(form.code()) || isBlank(form.name()) || form.componentClass() == null) {
            throw new BloodBankException(Kind.INVALID, "bloodbank.catalog.error.typeFields");
        }
        String code = form.code().trim().toUpperCase(Locale.ROOT);
        if (code.length() > 40 || form.name().trim().length() > 120) {
            throw new BloodBankException(Kind.INVALID, "bloodbank.catalog.error.typeFields");
        }
        if (typeDAO.getByCode(code).isPresent()) {
            throw new BloodBankException(Kind.CONFLICT, "bloodbank.catalog.error.typeExists", code);
        }
        BloodProductType type = new BloodProductType();
        type.setCode(code);
        type.setName(form.name().trim());
        type.setComponentClass(form.componentClass());
        type.setRequiresCrossmatch(Boolean.TRUE.equals(form.requiresCrossmatch()));
        type.setActive(form.active() == null || form.active());
        type.setSysUserId(sysUserId);
        typeDAO.insert(type);
        return BloodProductCatalogService.view(type);
    }

    @Override
    public ProductTypeView updateType(Long id, ProductTypeForm form, String sysUserId) {
        BloodProductType type = typeDAO.get(id).orElseThrow(() -> new BloodBankException(Kind.NOT_FOUND,
                "bloodbank.catalog.error.typeNotFound", String.valueOf(id)));
        if (form == null) {
            throw new BloodBankException(Kind.INVALID, "bloodbank.catalog.error.typeFields");
        }
        // the code and the component class are what units and rules hang on: fixed once
        // created
        if (form.name() != null) {
            if (form.name().isBlank() || form.name().trim().length() > 120) {
                throw new BloodBankException(Kind.INVALID, "bloodbank.catalog.error.typeFields");
            }
            type.setName(form.name().trim());
        }
        if (form.requiresCrossmatch() != null) {
            type.setRequiresCrossmatch(form.requiresCrossmatch());
        }
        if (form.active() != null) {
            type.setActive(form.active());
        }
        type.setSysUserId(sysUserId);
        typeDAO.update(type);
        return BloodProductCatalogService.view(type);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductCodeView> getCodes() {
        return codeDAO.getAllWithTypes().stream().map(BloodProductCatalogServiceImpl::view).toList();
    }

    @Override
    public ProductCodeView createCode(ProductCodeForm form, String sysUserId) {
        if (form == null || isBlank(form.isbtCodePrefix()) || form.productTypeId() == null) {
            throw new BloodBankException(Kind.INVALID, "bloodbank.catalog.error.codeFields");
        }
        String prefix = normalisePrefix(form.isbtCodePrefix());
        if (codeDAO.getByPrefix(prefix).isPresent()) {
            throw new BloodBankException(Kind.CONFLICT, "bloodbank.catalog.error.codeExists", prefix);
        }
        BloodProductCode code = new BloodProductCode();
        code.setIsbtCodePrefix(prefix);
        code.setProductType(requireType(form.productTypeId()));
        code.setDescription(trimTo(form.description(), 255));
        code.setActive(form.active() == null || form.active());
        code.setSysUserId(sysUserId);
        codeDAO.insert(code);
        return view(code);
    }

    @Override
    public ProductCodeView updateCode(Long id, ProductCodeForm form, String sysUserId) {
        BloodProductCode code = codeDAO.get(id).orElseThrow(() -> new BloodBankException(Kind.NOT_FOUND,
                "bloodbank.catalog.error.codeNotFound", String.valueOf(id)));
        if (form == null) {
            throw new BloodBankException(Kind.INVALID, "bloodbank.catalog.error.codeFields");
        }
        if (form.productTypeId() != null) {
            code.setProductType(requireType(form.productTypeId()));
        }
        if (form.description() != null) {
            code.setDescription(trimTo(form.description(), 255));
        }
        if (form.active() != null) {
            code.setActive(form.active());
        }
        code.setSysUserId(sysUserId);
        codeDAO.update(code);
        return view(code);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BloodProductType> resolve(String productCode) {
        if (productCode == null || productCode.length() < MIN_PREFIX) {
            return Optional.empty();
        }
        List<String> prefixes = new ArrayList<>();
        for (int length = Math.min(productCode.length(), PRODUCT_CODE_LENGTH); length >= MIN_PREFIX; length--) {
            prefixes.add(productCode.substring(0, length));
        }
        return codeDAO.getActiveByPrefixes(prefixes).stream().filter(c -> c.getProductType().isActive())
                .max(Comparator.comparingInt(c -> c.getIsbtCodePrefix().length()))
                .map(BloodProductCode::getProductType);
    }

    private BloodProductType requireType(Long typeId) {
        return typeDAO.get(typeId).orElseThrow(() -> new BloodBankException(Kind.NOT_FOUND,
                "bloodbank.catalog.error.typeNotFound", String.valueOf(typeId)));
    }

    static String normalisePrefix(String raw) {
        String prefix = raw.trim().toUpperCase(Locale.ROOT);
        if (prefix.startsWith("=<")) {
            prefix = prefix.substring(2);
        }
        if (prefix.length() < MIN_PREFIX || prefix.length() > PRODUCT_CODE_LENGTH
                || !prefix.matches("[A-Z][0-9]{4}[0-9A-Z]{0,3}")) {
            throw new BloodBankException(Kind.INVALID, "bloodbank.catalog.error.codePrefix", raw);
        }
        return prefix;
    }

    private static ProductCodeView view(BloodProductCode code) {
        return new ProductCodeView(code.getId(), code.getIsbtCodePrefix(), code.getDescription(), code.isActive(),
                BloodProductCatalogService.view(code.getProductType()));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimTo(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}
