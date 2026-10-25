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
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.sql.Timestamp;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.ReservationStatus;
import org.openelisglobal.common.valueholder.BaseObject;

/**
 * A unit held for a patient. For red cells it names the validated crossmatch
 * result that covers the unit: OpenELIS's crossmatch result carries no unit
 * number, so the link is made here, at reservation.
 */
@Entity
@Table(name = "blood_unit_reservation")
public class BloodUnitReservation extends BaseObject<Long> {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "blood_unit_reservation_generator")
    @SequenceGenerator(name = "blood_unit_reservation_generator", sequenceName = "blood_unit_reservation_seq", allocationSize = 1)
    @Column(name = "id")
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_id", nullable = false)
    private BloodUnit unit;

    @NotNull
    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    @Column(name = "crossmatch_result_id")
    private Long crossmatchResultId;

    @Column(name = "crossmatch_valid_until")
    private Timestamp crossmatchValidUntil;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private ReservationStatus status;

    @NotNull
    @Column(name = "reserved_by", nullable = false)
    private Integer reservedBy;

    @NotNull
    @Column(name = "reserved_at", nullable = false)
    private Timestamp reservedAt;

    @Column(name = "issued_at")
    private Timestamp issuedAt;

    @Column(name = "closed_at")
    private Timestamp closedAt;

    @Override
    public Long getId() {
        return id;
    }

    @Override
    public void setId(Long id) {
        this.id = id;
    }

    public BloodUnit getUnit() {
        return unit;
    }

    public void setUnit(BloodUnit unit) {
        this.unit = unit;
    }

    public Long getPatientId() {
        return patientId;
    }

    public void setPatientId(Long patientId) {
        this.patientId = patientId;
    }

    public Long getCrossmatchResultId() {
        return crossmatchResultId;
    }

    public void setCrossmatchResultId(Long crossmatchResultId) {
        this.crossmatchResultId = crossmatchResultId;
    }

    public Timestamp getCrossmatchValidUntil() {
        return crossmatchValidUntil;
    }

    public void setCrossmatchValidUntil(Timestamp crossmatchValidUntil) {
        this.crossmatchValidUntil = crossmatchValidUntil;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public void setStatus(ReservationStatus status) {
        this.status = status;
    }

    public Integer getReservedBy() {
        return reservedBy;
    }

    public void setReservedBy(Integer reservedBy) {
        this.reservedBy = reservedBy;
    }

    public Timestamp getReservedAt() {
        return reservedAt;
    }

    public void setReservedAt(Timestamp reservedAt) {
        this.reservedAt = reservedAt;
    }

    public Timestamp getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(Timestamp issuedAt) {
        this.issuedAt = issuedAt;
    }

    public Timestamp getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(Timestamp closedAt) {
        this.closedAt = closedAt;
    }
}
