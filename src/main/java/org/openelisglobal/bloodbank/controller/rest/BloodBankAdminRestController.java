package org.openelisglobal.bloodbank.controller.rest;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.openelisglobal.bloodbank.form.BloodBankForms.ProductCodeForm;
import org.openelisglobal.bloodbank.form.BloodBankForms.ProductTypeForm;
import org.openelisglobal.bloodbank.form.BloodBankViews.ProductCodeView;
import org.openelisglobal.bloodbank.form.BloodBankViews.ProductTypeView;
import org.openelisglobal.bloodbank.service.BloodBankSettings;
import org.openelisglobal.bloodbank.service.BloodBankSettingsService;
import org.openelisglobal.bloodbank.service.BloodProductCatalogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Configuration: settings and the product catalogue. Reading the product types
 * is open to bench users too, for the stock filters and the receipt screen.
 */
@RestController
@RequestMapping(value = "/rest/bloodbank", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize(BloodBankGuards.ADMIN)
public class BloodBankAdminRestController extends BloodBankRestSupport {

    @Autowired
    private BloodBankSettingsService settingsService;

    @Autowired
    private BloodProductCatalogService catalogService;

    @GetMapping("/settings")
    public BloodBankSettings settings() {
        return settingsService.get();
    }

    @PutMapping("/settings")
    @PreAuthorize(BloodBankGuards.ADMIN)
    public BloodBankSettings updateSettings(@RequestBody(required = false) Map<String, Object> changes,
            HttpServletRequest request) {
        return settingsService.update(changes, getSysUserId(request));
    }

    @GetMapping("/product-types")
    @PreAuthorize(BloodBankGuards.READ)
    public List<ProductTypeView> productTypes() {
        return catalogService.getTypes();
    }

    @PostMapping("/product-types")
    @PreAuthorize(BloodBankGuards.ADMIN)
    public ResponseEntity<ProductTypeView> createProductType(@RequestBody(required = false) ProductTypeForm form,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalogService.createType(form, getSysUserId(request)));
    }

    @PutMapping("/product-types/{id}")
    @PreAuthorize(BloodBankGuards.ADMIN)
    public ProductTypeView updateProductType(@PathVariable("id") Long id,
            @RequestBody(required = false) ProductTypeForm form, HttpServletRequest request) {
        return catalogService.updateType(id, form, getSysUserId(request));
    }

    @GetMapping("/product-codes")
    public List<ProductCodeView> productCodes() {
        return catalogService.getCodes();
    }

    @PostMapping("/product-codes")
    @PreAuthorize(BloodBankGuards.ADMIN)
    public ResponseEntity<ProductCodeView> createProductCode(@RequestBody(required = false) ProductCodeForm form,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalogService.createCode(form, getSysUserId(request)));
    }

    @PutMapping("/product-codes/{id}")
    @PreAuthorize(BloodBankGuards.ADMIN)
    public ProductCodeView updateProductCode(@PathVariable("id") Long id,
            @RequestBody(required = false) ProductCodeForm form, HttpServletRequest request) {
        return catalogService.updateCode(id, form, getSysUserId(request));
    }
}
