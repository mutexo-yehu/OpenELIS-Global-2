package org.openelisglobal.bloodbank.valueholder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.util.UUID;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.Abo;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.RhD;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.UnitStatus;
import org.openelisglobal.common.valueholder.BaseObject;

/**
 * One physical blood unit. A donation is split into several components, so a
 * unit is identified by its donation identification number (DIN) together with
 * its product code.
 */
@Entity
@Table(name = "blood_unit", uniqueConstraints = @UniqueConstraint(name = "uq_blood_unit_din_product", columnNames = {
        "donation_number", "product_code" }))
public class BloodUnit extends BaseObject<Long> {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "blood_unit_generator")
    @SequenceGenerator(name = "blood_unit_generator", sequenceName = "blood_unit_seq", allocationSize = 1)
    @Column(name = "id")
    private Long id;

    /** Reserved for a later export as a FHIR BiologicallyDerivedProduct. */
    @Column(name = "fhir_uuid", nullable = false, unique = true)
    private UUID fhirUuid;

    @NotNull
    @Size(min = 13, max = 13)
    @Column(name = "donation_number", nullable = false, length = 13)
    private String donationNumber;

    @NotNull
    @Size(max = 8)
    @Column(name = "product_code", nullable = false, length = 8)
    private String productCode;

    @NotNull
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "product_type_id", nullable = false)
    private BloodProductType productType;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "abo", nullable = false, length = 2)
    private Abo abo;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "rhd", nullable = false, length = 3)
    private RhD rhd;

    @NotNull
    @Column(name = "expires_at", nullable = false)
    private Timestamp expiresAt;

    @Column(name = "collected_at")
    private Timestamp collectedAt;

    @Size(max = 120)
    @Column(name = "supplier", length = 120)
    private String supplier;

    @Column(name = "volume_ml")
    private Integer volumeMl;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private UnitStatus status;

    @NotNull
    @Column(name = "received_at", nullable = false)
    private Timestamp receivedAt;

    @NotNull
    @Column(name = "received_by", nullable = false)
    private Integer receivedBy;

    /** The patient the unit is reserved for or issued to, else null. */
    @Column(name = "patient_id")
    private Long patientId;

    @PrePersist
    void assignFhirUuid() {
        if (fhirUuid == null) {
            fhirUuid = UUID.randomUUID();
        }
    }

    @Override
    public Long getId() {
        return id;
    }

    @Override
    public void setId(Long id) {
        this.id = id;
    }

    public UUID getFhirUuid() {
        return fhirUuid;
    }

    public void setFhirUuid(UUID fhirUuid) {
        this.fhirUuid = fhirUuid;
    }

    public String getDonationNumber() {
        return donationNumber;
    }

    public void setDonationNumber(String donationNumber) {
        this.donationNumber = donationNumber;
    }

    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }

    public BloodProductType getProductType() {
        return productType;
    }

    public void setProductType(BloodProductType productType) {
        this.productType = productType;
    }

    public Abo getAbo() {
        return abo;
    }

    public void setAbo(Abo abo) {
        this.abo = abo;
    }

    public RhD getRhd() {
        return rhd;
    }

    public void setRhd(RhD rhd) {
        this.rhd = rhd;
    }

    public Timestamp getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Timestamp expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Timestamp getCollectedAt() {
        return collectedAt;
    }

    public void setCollectedAt(Timestamp collectedAt) {
        this.collectedAt = collectedAt;
    }

    public String getSupplier() {
        return supplier;
    }

    public void setSupplier(String supplier) {
        this.supplier = supplier;
    }

    public Integer getVolumeMl() {
        return volumeMl;
    }

    public void setVolumeMl(Integer volumeMl) {
        this.volumeMl = volumeMl;
    }

    public UnitStatus getStatus() {
        return status;
    }

    public void setStatus(UnitStatus status) {
        this.status = status;
    }

    public Timestamp getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Timestamp receivedAt) {
        this.receivedAt = receivedAt;
    }

    public Integer getReceivedBy() {
        return receivedBy;
    }

    public void setReceivedBy(Integer receivedBy) {
        this.receivedBy = receivedBy;
    }

    public Long getPatientId() {
        return patientId;
    }

    public void setPatientId(Long patientId) {
        this.patientId = patientId;
    }
}
