package org.openelisglobal.bloodbank.service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;
import org.openelisglobal.bloodbank.form.BloodBankViews.EmergencyIssueView;
import org.openelisglobal.bloodbank.form.BloodBankViews.EventView;
import org.openelisglobal.bloodbank.form.BloodBankViews.ReservationView;
import org.openelisglobal.bloodbank.form.BloodBankViews.UnitView;
import org.openelisglobal.bloodbank.valueholder.BloodUnit;
import org.openelisglobal.bloodbank.valueholder.BloodUnitEvent;
import org.openelisglobal.bloodbank.valueholder.BloodUnitReservation;

/** Entity → view, inside the service transaction. */
final class BloodBankMapper {

    private BloodBankMapper() {
    }

    static String iso(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    static UnitView unit(BloodUnit unit, Instant now, int expiryWarnHours) {
        Instant expires = instant(unit.getExpiresAt());
        boolean expired = expires == null || !expires.isAfter(now);
        boolean expiringSoon = !expired && expires.isBefore(now.plus(Duration.ofHours(expiryWarnHours)));
        return new UnitView(unit.getId(), unit.getFhirUuid() == null ? null : unit.getFhirUuid().toString(),
                unit.getDonationNumber(), unit.getProductCode(), BloodProductCatalogService.view(unit.getProductType()),
                unit.getAbo(), unit.getRhd(), iso(unit.getExpiresAt()), iso(unit.getCollectedAt()), unit.getSupplier(),
                unit.getVolumeMl(), unit.getStatus(), iso(unit.getReceivedAt()), unit.getReceivedBy(),
                unit.getPatientId(), expired, expiringSoon);
    }

    static ReservationView reservation(BloodUnitReservation reservation) {
        if (reservation == null) {
            return null;
        }
        return new ReservationView(reservation.getId(), reservation.getUnit().getId(), reservation.getPatientId(),
                reservation.getCrossmatchResultId(), iso(reservation.getCrossmatchValidUntil()),
                reservation.getStatus(), reservation.getReservedBy(), iso(reservation.getReservedAt()),
                iso(reservation.getIssuedAt()), iso(reservation.getClosedAt()));
    }

    /** @param userName system user id → display name */
    static EmergencyIssueView emergency(BloodUnitReservation reservation, Function<Integer, String> userName) {
        BloodUnit unit = reservation.getUnit();
        Integer checkedBy = reservation.getRetrospectiveCheckedBy();
        return new EmergencyIssueView(reservation.getId(), unit.getId(), unit.getDonationNumber(),
                unit.getProductCode(), BloodProductCatalogService.view(unit.getProductType()), unit.getAbo(),
                unit.getRhd(), unit.getStatus(), iso(reservation.getIssuedAt()), reservation.getReservedBy(),
                userName.apply(reservation.getReservedBy()), reservation.getEmergencyClinician(),
                reservation.getEmergencyReason(), reservation.isEmergencyRhdPositiveAcknowledged(),
                reservation.getRetrospectiveResult(), reservation.getRetrospectiveNote(), checkedBy,
                checkedBy == null ? null : userName.apply(checkedBy), iso(reservation.getRetrospectiveCheckedAt()));
    }

    /** @param userName system user id → display name */
    static EventView event(BloodUnitEvent event, Function<Integer, String> userName) {
        BloodUnit unit = event.getUnit();
        return new EventView(event.getId(), unit.getId(), unit.getDonationNumber(), unit.getProductCode(),
                event.getEventType(), event.getPatientId(), event.getReservationId(), event.getPerformedBy(),
                userName.apply(event.getPerformedBy()), event.getSecondCheckerId(),
                event.getSecondCheckerId() == null ? null : userName.apply(event.getSecondCheckerId()),
                iso(event.getOccurredAt()), event.getReason(), event.getLocation(), event.getCollectorName(),
                event.getDetails());
    }
}
