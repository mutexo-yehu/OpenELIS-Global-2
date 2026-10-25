package org.openelisglobal.bloodbank.valueholder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.ComponentClass;
import org.openelisglobal.common.valueholder.BaseObject;

/** A component type the lab stocks, such as red cells or platelets. */
@Entity
@Table(name = "blood_product_type")
public class BloodProductType extends BaseObject<Long> {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "blood_product_type_generator")
    @SequenceGenerator(name = "blood_product_type_generator", sequenceName = "blood_product_type_seq", allocationSize = 1)
    @Column(name = "id")
    private Long id;

    @NotNull
    @Size(max = 40)
    @Column(name = "code", nullable = false, length = 40, unique = true)
    private String code;

    @NotNull
    @Size(max = 120)
    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "component_class", nullable = false, length = 20)
    private ComponentClass componentClass;

    @Column(name = "requires_crossmatch", nullable = false)
    private boolean requiresCrossmatch;

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

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ComponentClass getComponentClass() {
        return componentClass;
    }

    public void setComponentClass(ComponentClass componentClass) {
        this.componentClass = componentClass;
    }

    public boolean isRequiresCrossmatch() {
        return requiresCrossmatch;
    }

    public void setRequiresCrossmatch(boolean requiresCrossmatch) {
        this.requiresCrossmatch = requiresCrossmatch;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    /**
     * Red cells and whole blood always need a crossmatch, whatever the flag says;
     * other components need one only when the site asks for it.
     */
    public boolean needsCrossmatch() {
        return requiresCrossmatch || (componentClass != null && componentClass.carriesRedCells());
    }
}
