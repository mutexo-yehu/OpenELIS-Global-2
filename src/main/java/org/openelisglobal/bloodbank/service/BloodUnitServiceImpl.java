package org.openelisglobal.bloodbank.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.openelisglobal.bloodbank.dao.BloodProductTypeDAO;
import org.openelisglobal.bloodbank.dao.BloodUnitDAO;
import org.openelisglobal.bloodbank.dao.BloodUnitEventDAO;
import org.openelisglobal.bloodbank.dao.BloodUnitReservationDAO;
import org.openelisglobal.bloodbank.form.BloodBankForms.ReceiveLine;
import org.openelisglobal.bloodbank.form.BloodBankViews.EventView;
import org.openelisglobal.bloodbank.form.BloodBankViews.LineError;
import org.openelisglobal.bloodbank.form.BloodBankViews.ParsedLabel;
import org.openelisglobal.bloodbank.form.BloodBankViews.ProductTypeView;
import org.openelisglobal.bloodbank.form.BloodBankViews.ReceiveLineResult;
import org.openelisglobal.bloodbank.form.BloodBankViews.StockSummary;
import org.openelisglobal.bloodbank.form.BloodBankViews.SummaryRow;
import org.openelisglobal.bloodbank.form.BloodBankViews.UnitActionResult;
import org.openelisglobal.bloodbank.form.BloodBankViews.UnitDetail;
import org.openelisglobal.bloodbank.form.BloodBankViews.UnitView;
import org.openelisglobal.bloodbank.isbt.Isbt128Parser;
import org.openelisglobal.bloodbank.isbt.Isbt128Parser.BloodGroup;
import org.openelisglobal.bloodbank.isbt.Isbt128Parser.Isbt128Label;
import org.openelisglobal.bloodbank.service.BloodBankException.Kind;
import org.openelisglobal.bloodbank.service.BloodUnitTrail.Entry;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.Abo;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.EventType;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.ReservationStatus;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.RhD;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.UnitStatus;
import org.openelisglobal.bloodbank.valueholder.BloodProductType;
import org.openelisglobal.bloodbank.valueholder.BloodUnit;
import org.openelisglobal.bloodbank.valueholder.BloodUnitEvent;
import org.openelisglobal.bloodbank.valueholder.BloodUnitReservation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class BloodUnitServiceImpl implements BloodUnitService {

    static final String ERR_DUPLICATE = "bloodbank.receive.error.duplicate";
    static final String ERR_EXPIRED = "bloodbank.receive.error.expired";
    static final String ERR_UNKNOWN_PRODUCT = "bloodbank.receive.error.unknownProductCode";
    static final String ERR_MISSING = "bloodbank.receive.error.missingField";
    static final String ERR_COLLECTED = "bloodbank.receive.error.collectedAt";
    static final String ERR_VOLUME = "bloodbank.receive.error.volume";
    static final String ERR_SUPPLIER = "bloodbank.receive.error.supplier";
    static final int MAX_LINES = 200;

    /** Stock that is still in the lab: counted on the board. */
    private static final List<UnitStatus> IN_STOCK = List.of(UnitStatus.AVAILABLE, UnitStatus.RESERVED);

    @Autowired
    private BloodUnitDAO unitDAO;

    @Autowired
    private BloodProductTypeDAO typeDAO;

    @Autowired
    private BloodUnitReservationDAO reservationDAO;

    @Autowired
    private BloodUnitEventDAO eventDAO;

    @Autowired
    private BloodProductCatalogService catalogService;

    @Autowired
    private BloodBankSettingsService settingsService;

    @Autowired
    private BloodUnitTrail trail;

    private Clock clock = Clock.systemDefaultZone();

    void setClock(Clock clock) {
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public ParsedLabel parse(List<String> scans) {
        Isbt128Label label = Isbt128Parser.parseScans(scans);
        List<LineError> errors = new ArrayList<>();
        for (int i = 0; i < label.getErrorCodes().size(); i++) {
            errors.add(new LineError(label.getErrorCodes().get(i), List.of(label.getErrorInputs().get(i))));
        }
        ProductTypeView type = null;
        if (label.getProductCode() != null) {
            Optional<BloodProductType> resolved = catalogService.resolve(label.getProductCode());
            if (resolved.isPresent()) {
                type = BloodProductCatalogService.view(resolved.get());
            } else {
                errors.add(new LineError(ERR_UNKNOWN_PRODUCT, List.of(label.getProductCode())));
            }
        }
        String expires = label.getExpiresAt() == null ? null
                : label.getExpiresAt().atZone(clock.getZone()).toInstant().toString();
        return new ParsedLabel(label.getDonationNumber(), label.getFlags(), label.getAbo(), label.getRhd(),
                label.getProductCode(), type, expires, errors);
    }

    @Override
    public List<ReceiveLineResult> receive(List<ReceiveLine> lines, String sysUserId) {
        if (lines == null || lines.isEmpty()) {
            throw new BloodBankException(Kind.INVALID, "bloodbank.receive.error.noLines");
        }
        if (lines.size() > MAX_LINES) {
            throw new BloodBankException(Kind.INVALID, "bloodbank.receive.error.tooManyLines",
                    String.valueOf(MAX_LINES));
        }
        Integer user = userId(sysUserId);
        Instant now = clock.instant();
        Set<String> seenInBatch = new HashSet<>();
        List<ReceiveLineResult> results = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            results.add(receiveLine(i, lines.get(i), user, now, seenInBatch));
        }
        return results;
    }

    /**
     * Validates one line completely, then saves it; nothing is saved for a refused
     * line.
     */
    private ReceiveLineResult receiveLine(int index, ReceiveLine line, Integer user, Instant now,
            Set<String> seenInBatch) {
        List<LineError> errors = new ArrayList<>();
        if (line == null) {
            return new ReceiveLineResult(index, false, null, null, null,
                    List.of(new LineError(ERR_MISSING, List.of("line"))));
        }
        Isbt128Label label = line.scans() == null || line.scans().isEmpty() ? null
                : Isbt128Parser.parseScans(line.scans());
        if (label != null) {
            for (int i = 0; i < label.getErrorCodes().size(); i++) {
                errors.add(new LineError(label.getErrorCodes().get(i), List.of(label.getErrorInputs().get(i))));
            }
        }
        String din = label == null ? null : label.getDonationNumber();
        String productCode = label == null ? null : label.getProductCode();
        Abo abo = label == null ? null : label.getAbo();
        RhD rhd = label == null ? null : label.getRhd();
        LocalDateTime expires = label == null ? null : label.getExpiresAt();

        List<String> typedErrors = new ArrayList<>();
        if (!isBlank(line.donationNumber())) {
            din = Isbt128Parser.typedDonationNumber(line.donationNumber(), typedErrors);
        }
        if (!isBlank(line.productCode())) {
            productCode = Isbt128Parser.typedProductCode(line.productCode(), typedErrors);
        }
        if (!isBlank(line.bloodGroup())) {
            BloodGroup group = Isbt128Parser.typedGroup(line.bloodGroup(), typedErrors);
            abo = group == null ? null : group.abo();
            rhd = group == null ? null : group.rhd();
        }
        if (!isBlank(line.expiresAt())) {
            expires = Isbt128Parser.typedExpiry(line.expiresAt(), typedErrors);
        }
        typedErrors.forEach(code -> errors.add(new LineError(code, List.of())));

        if (din == null && typedErrors.stream().noneMatch(c -> c.startsWith("bloodbank.isbt.error.din"))) {
            errors.add(new LineError(ERR_MISSING, List.of("donationNumber")));
        }
        if (productCode == null && !typedErrors.contains(Isbt128Parser.ERR_PRODUCT_CODE)) {
            errors.add(new LineError(ERR_MISSING, List.of("productCode")));
        }
        if ((abo == null || rhd == null) && !typedErrors.contains(Isbt128Parser.ERR_GROUP)) {
            errors.add(new LineError(ERR_MISSING, List.of("bloodGroup")));
        }
        if (expires == null && !typedErrors.contains(Isbt128Parser.ERR_EXPIRY)) {
            errors.add(new LineError(ERR_MISSING, List.of("expiresAt")));
        }
        Instant expiresAt = expires == null ? null : expires.atZone(clock.getZone()).toInstant();
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            errors.add(new LineError(ERR_EXPIRED, List.of(expiresAt.toString())));
        }
        Timestamp collectedAt = null;
        if (!isBlank(line.collectedAt())) {
            collectedAt = localTimestamp(line.collectedAt(), clock.getZone());
            if (collectedAt == null) {
                errors.add(new LineError(ERR_COLLECTED, List.of(line.collectedAt())));
            }
        }
        if (line.volumeMl() != null && (line.volumeMl() <= 0 || line.volumeMl() > 2000)) {
            errors.add(new LineError(ERR_VOLUME, List.of(String.valueOf(line.volumeMl()))));
        }
        if (line.supplier() != null && line.supplier().trim().length() > 120) {
            errors.add(new LineError(ERR_SUPPLIER, List.of()));
        }
        BloodProductType type = null;
        if (productCode != null) {
            type = catalogService.resolve(productCode).orElse(null);
            if (type == null) {
                errors.add(new LineError(ERR_UNKNOWN_PRODUCT, List.of(productCode)));
            }
        }
        if (din != null && productCode != null) {
            String key = din + "|" + productCode;
            if (!seenInBatch.add(key) || unitDAO.getByDonationNumberAndProductCode(din, productCode).isPresent()) {
                errors.add(new LineError(ERR_DUPLICATE, List.of(din, productCode)));
            }
        }
        if (!errors.isEmpty()) {
            return new ReceiveLineResult(index, false, null, din, productCode, errors);
        }

        BloodUnit unit = new BloodUnit();
        unit.setDonationNumber(din);
        unit.setProductCode(productCode);
        unit.setProductType(type);
        unit.setAbo(abo);
        unit.setRhd(rhd);
        unit.setExpiresAt(Timestamp.from(expiresAt));
        unit.setCollectedAt(collectedAt);
        unit.setSupplier(isBlank(line.supplier()) ? null : line.supplier().trim());
        unit.setVolumeMl(line.volumeMl());
        unit.setStatus(UnitStatus.AVAILABLE);
        unit.setReceivedAt(Timestamp.from(now));
        unit.setReceivedBy(user);
        unit.setSysUserId(String.valueOf(user));
        unitDAO.insert(unit);
        trail.record(unit, EventType.RECEIVED, user, now, new Entry(null, null, null, null, null, null,
                unit.getSupplier() == null ? null : "supplier: " + unit.getSupplier()));
        return new ReceiveLineResult(index, true, unit.getId(), din, productCode, List.of());
    }

    @Override
    @Transactional(readOnly = true)
    public List<UnitView> search(List<UnitStatus> statuses, Long productTypeId, Abo abo, RhD rhd,
            Integer expiringWithinHours, Long patientId) {
        if (expiringWithinHours != null && (expiringWithinHours < 0 || expiringWithinHours > 24 * 366)) {
            throw new BloodBankException(Kind.INVALID, "bloodbank.error.expiringWithinHours");
        }
        Instant now = clock.instant();
        Timestamp before = expiringWithinHours == null ? null
                : Timestamp.from(now.plus(Duration.ofHours(expiringWithinHours)));
        int warnHours = settingsService.get().expiryWarnHours();
        return unitDAO.search(statuses, productTypeId, abo, rhd, before).stream()
                .filter(u -> patientId == null || patientId.equals(u.getPatientId()))
                .map(u -> BloodBankMapper.unit(u, now, warnHours)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public StockSummary summary() {
        Instant now = clock.instant();
        int warnHours = settingsService.get().expiryWarnHours();
        Map<Long, ProductTypeView> types = new HashMap<>();
        typeDAO.getAll().forEach(t -> types.put(t.getId(), BloodProductCatalogService.view(t)));
        List<SummaryRow> rows = new ArrayList<>();
        for (Object[] row : unitDAO.countByTypeAndGroup(IN_STOCK)) {
            rows.add(new SummaryRow(types.get((Long) row[0]), (Abo) row[1], (RhD) row[2], (Long) row[3]));
        }
        long expired = unitDAO.countExpiringBefore(IN_STOCK, Timestamp.from(now));
        long expiringOrExpired = unitDAO.countExpiringBefore(IN_STOCK,
                Timestamp.from(now.plus(Duration.ofHours(warnHours))));
        return new StockSummary(rows, expiringOrExpired - expired, expired, warnHours);
    }

    @Override
    @Transactional(readOnly = true)
    public UnitDetail get(Long unitId) {
        BloodUnit unit = unitDAO.get(unitId).orElseThrow(() -> notFound(unitId));
        Function<Integer, String> names = trail.userNames();
        List<EventView> events = eventDAO.getByUnit(unitId).stream().map(e -> BloodBankMapper.event(e, names)).toList();
        BloodUnitReservation active = reservationDAO.getForUnit(unitId, ReservationStatus.ACTIVE)
                .orElse(reservationDAO.getForUnit(unitId, ReservationStatus.ISSUED).orElse(null));
        return new UnitDetail(BloodBankMapper.unit(unit, clock.instant(), settingsService.get().expiryWarnHours()),
                BloodBankMapper.reservation(active), events);
    }

    @Override
    public UnitActionResult quarantine(Long unitId, String reason, String sysUserId) {
        requireReason(reason);
        return move(unitId, UnitStatus.QUARANTINED, EventType.QUARANTINED, reason, sysUserId);
    }

    @Override
    public UnitActionResult release(Long unitId, String reason, String sysUserId) {
        BloodUnit unit = unitDAO.getForUpdate(unitId).orElseThrow(() -> notFound(unitId));
        if (unit.getStatus() != UnitStatus.QUARANTINED) {
            throw new BloodBankException(Kind.CONFLICT, "bloodbank.error.notQuarantined",
                    String.valueOf(unit.getStatus()));
        }
        return move(unitId, UnitStatus.AVAILABLE, EventType.RELEASED, reason, sysUserId);
    }

    @Override
    public UnitActionResult discard(Long unitId, String reason, String sysUserId) {
        requireReason(reason);
        return move(unitId, UnitStatus.DISCARDED, EventType.DISCARDED, reason, sysUserId);
    }

    @Override
    public UnitActionResult returnToSupplier(Long unitId, String reason, String sysUserId) {
        requireReason(reason);
        return move(unitId, UnitStatus.RETURNED_TO_SUPPLIER, EventType.RETURNED_TO_SUPPLIER, reason, sysUserId);
    }

    /**
     * A stock action: lock, check the move is allowed, release any live reservation
     * (with its own entry), change state, add the entry.
     */
    private UnitActionResult move(Long unitId, UnitStatus target, EventType eventType, String reason,
            String sysUserId) {
        Integer user = userId(sysUserId);
        BloodUnit unit = unitDAO.getForUpdate(unitId).orElseThrow(() -> notFound(unitId));
        if (unit.getStatus() == UnitStatus.ISSUED || !UnitStatusTransitions.isAllowed(unit.getStatus(), target)) {
            throw new BloodBankException(Kind.CONFLICT, "bloodbank.error.transition", String.valueOf(unit.getStatus()),
                    target.name());
        }
        Instant now = clock.instant();
        List<BloodUnitEvent> events = new ArrayList<>();
        Optional<BloodUnitReservation> live = reservationDAO.getForUnit(unitId, ReservationStatus.ACTIVE);
        if (live.isPresent()) {
            BloodUnitReservation reservation = live.get();
            reservation.setStatus(ReservationStatus.RELEASED);
            reservation.setClosedAt(Timestamp.from(now));
            reservation.setSysUserId(String.valueOf(user));
            reservationDAO.update(reservation);
            events.add(trail.record(unit, EventType.UNRESERVED, user, now,
                    Entry.of(reservation.getPatientId(), reservation.getId(), reason)));
            unit.setPatientId(null);
        }
        unit.setStatus(target);
        unit.setSysUserId(String.valueOf(user));
        unitDAO.update(unit);
        events.add(trail.record(unit, eventType, user, now, Entry.of(null, null, reason)));
        Function<Integer, String> names = trail.userNames();
        return new UnitActionResult(BloodBankMapper.unit(unit, now, settingsService.get().expiryWarnHours()),
                events.stream().map(e -> BloodBankMapper.event(e, names)).toList());
    }

    static Integer userId(String sysUserId) {
        try {
            return Integer.valueOf(sysUserId);
        } catch (NumberFormatException | NullPointerException e) {
            throw new BloodBankException(Kind.INVALID, "bloodbank.error.noUser");
        }
    }

    static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BloodBankException(Kind.INVALID, "bloodbank.error.reasonRequired");
        }
        if (reason.trim().length() > 500) {
            throw new BloodBankException(Kind.INVALID, "bloodbank.error.reasonTooLong", "500");
        }
    }

    static BloodBankException notFound(Long unitId) {
        return new BloodBankException(Kind.NOT_FOUND, "bloodbank.error.unitNotFound", String.valueOf(unitId));
    }

    /** ISO local date-time (or date) in the lab's zone; null when unreadable. */
    static Timestamp localTimestamp(String text, ZoneId zone) {
        try {
            String value = text.trim();
            LocalDateTime local = value.length() == 10 ? LocalDateTime.parse(value + "T00:00")
                    : LocalDateTime.parse(value.length() > 16 ? value.substring(0, 16) : value);
            return Timestamp.from(local.atZone(zone).toInstant());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
