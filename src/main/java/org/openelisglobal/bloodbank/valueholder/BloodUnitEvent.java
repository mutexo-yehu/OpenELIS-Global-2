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
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import org.hibernate.annotations.Immutable;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.EventType;
import org.openelisglobal.common.valueholder.BaseObject;

/**
 * One entry of a unit's traceability trail. Entries are only ever added:
 * Hibernate treats the entity as immutable, the DAO has no update or delete,
 * and a database trigger refuses UPDATE and DELETE on the table.
 */
@Entity
@Immutable
@Table(name = "blood_unit_event")
public class BloodUnitEvent extends BaseObject<Long> {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "blood_unit_event_generator")
    @SequenceGenerator(name = "blood_unit_event_generator", sequenceName = "blood_unit_event_seq", allocationSize = 1)
    @Column(name = "id")
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_id", nullable = false, updatable = false)
    private BloodUnit unit;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 24, updatable = false)
    private EventType eventType;

    @Column(name = "patient_id", updatable = false)
    private Long patientId;

    @Column(name = "reservation_id", updatable = false)
    private Long reservationId;

    @NotNull
    @Column(name = "performed_by", nullable = false, updatable = false)
    private Integer performedBy;

    @Column(name = "second_checker_id", updatable = false)
    private Integer secondCheckerId;

    @NotNull
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Timestamp occurredAt;

    @Size(max = 500)
    @Column(name = "reason", length = 500, updatable = false)
    private String reason;

    @Size(max = 120)
    @Column(name = "location", length = 120, updatable = false)
    private String location;

    @Size(max = 120)
    @Column(name = "collector_name", length = 120, updatable = false)
    private String collectorName;

    @Column(name = "details", columnDefinition = "TEXT", updatable = false)
    private String details;

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

    public EventType getEventType() {
        return eventType;
    }

    public void setEventType(EventType eventType) {
        this.eventType = eventType;
    }

    public Long getPatientId() {
        return patientId;
    }

    public void setPatientId(Long patientId) {
        this.patientId = patientId;
    }

    public Long getReservationId() {
        return reservationId;
    }

    public void setReservationId(Long reservationId) {
        this.reservationId = reservationId;
    }

    public Integer getPerformedBy() {
        return performedBy;
    }

    public void setPerformedBy(Integer performedBy) {
        this.performedBy = performedBy;
    }

    public Integer getSecondCheckerId() {
        return secondCheckerId;
    }

    public void setSecondCheckerId(Integer secondCheckerId) {
        this.secondCheckerId = secondCheckerId;
    }

    public Timestamp getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Timestamp occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getCollectorName() {
        return collectorName;
    }

    public void setCollectorName(String collectorName) {
        this.collectorName = collectorName;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }
}
