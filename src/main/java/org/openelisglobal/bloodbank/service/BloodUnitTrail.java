package org.openelisglobal.bloodbank.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.openelisglobal.bloodbank.dao.BloodUnitEventDAO;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.EventType;
import org.openelisglobal.bloodbank.valueholder.BloodUnit;
import org.openelisglobal.bloodbank.valueholder.BloodUnitEvent;
import org.openelisglobal.systemuser.service.SystemUserService;
import org.openelisglobal.systemuser.valueholder.SystemUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Adds entries to the unit trail, in the caller's transaction: every state
 * change writes its entry in the same transaction as the change.
 */
@Component
public class BloodUnitTrail {

    @Autowired
    private BloodUnitEventDAO eventDAO;

    @Autowired
    private SystemUserService systemUserService;

    /** Everything an entry may say besides the unit, the type, who and when. */
    public record Entry(Long patientId, Long reservationId, Integer secondCheckerId, String reason, String location,
            String collectorName, String details) {

        public static Entry of(Long patientId, Long reservationId, String reason) {
            return new Entry(patientId, reservationId, null, reason, null, null, null);
        }
    }

    public BloodUnitEvent record(BloodUnit unit, EventType type, Integer performedBy, Instant when, Entry entry) {
        BloodUnitEvent event = new BloodUnitEvent();
        event.setUnit(unit);
        event.setEventType(type);
        event.setPerformedBy(performedBy);
        event.setOccurredAt(Timestamp.from(when));
        event.setPatientId(entry.patientId());
        event.setReservationId(entry.reservationId());
        event.setSecondCheckerId(entry.secondCheckerId());
        event.setReason(limit(entry.reason(), 500));
        event.setLocation(limit(entry.location(), 120));
        event.setCollectorName(limit(entry.collectorName(), 120));
        event.setDetails(entry.details());
        event.setSysUserId(String.valueOf(performedBy));
        eventDAO.append(event);
        return event;
    }

    /** User id → display name, looked up once per id for one response. */
    public Function<Integer, String> userNames() {
        Map<Integer, String> cache = new HashMap<>();
        return id -> {
            if (id == null) {
                return null;
            }
            return cache.computeIfAbsent(id, key -> {
                SystemUser user = systemUserService.getUserById(String.valueOf(key));
                return user == null ? String.valueOf(key) : firstLast(user);
            });
        };
    }

    /** "First Last", or the login name when the account has no name. */
    static String firstLast(SystemUser user) {
        String name = String.join(" ", Stream.of(user.getFirstName(), user.getLastName())
                .filter(part -> part != null && !part.isBlank()).map(String::trim).toList());
        if (!name.isEmpty()) {
            return name;
        }
        return user.getLoginName() != null ? user.getLoginName() : String.valueOf(user.getId());
    }

    private static String limit(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}
