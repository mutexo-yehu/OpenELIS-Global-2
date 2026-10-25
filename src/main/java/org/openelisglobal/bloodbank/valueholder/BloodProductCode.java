package org.openelisglobal.bloodbank.valueholder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.openelisglobal.common.valueholder.BaseObject;

/**
 * An ISBT 128 product code, or its leading characters, mapped to the component
 * type it stands for. Each site maps its own codes.
 */
@Entity
@Table(name = "blood_product_code")
public class BloodProductCode extends BaseObject<Long> {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "blood_product_code_generator")
    @SequenceGenerator(name = "blood_product_code_generator", sequenceName = "blood_product_code_seq", allocationSize = 1)
    @Column(name = "id")
    private Long id;

    @NotNull
    @Size(min = 5, max = 8)
    @Column(name = "isbt_code_prefix", nullable = false, length = 8, unique = true)
    private String isbtCodePrefix;

    @NotNull
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "product_type_id", nullable = false)
    private BloodProductType productType;

    @Size(max = 255)
    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Override
    public Long getId() {
        return id;
    }

    @Override
    public void setId(Long id) {
        this.id = id;
    }

    public String getIsbtCodePrefix() {
        return isbtCodePrefix;
    }

    public void setIsbtCodePrefix(String isbtCodePrefix) {
        this.isbtCodePrefix = isbtCodePrefix;
    }

    public BloodProductType getProductType() {
        return productType;
    }

    public void setProductType(BloodProductType productType) {
        this.productType = productType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
