package org.openelisglobal.bloodbank.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.openelisglobal.bloodbank.dao.BloodUnitDAO;
import org.openelisglobal.bloodbank.dao.BloodUnitReservationDAO;
import org.openelisglobal.bloodbank.form.BloodBankForms.IssueRequest;
import org.openelisglobal.bloodbank.form.BloodBankViews.CandidateUnit;
import org.openelisglobal.bloodbank.form.BloodBankViews.IssueCheck;
import org.openelisglobal.bloodbank.form.BloodBankViews.ReservationView;
import org.openelisglobal.bloodbank.form.BloodBankViews.UnitActionResult;
import org.openelisglobal.bloodbank.safety.BloodCompatibility;
import org.openelisglobal.bloodbank.safety.IssueSafetyChecker;
import org.openelisglobal.bloodbank.safety.IssueSafetyChecker.IssueSnapshot;
import org.openelisglobal.bloodbank.safety.IssueSafetyChecker.ReservationFacts;
import org.openelisglobal.bloodbank.safety.IssueSafetyChecker.SecondCheckOutcome;
import org.openelisglobal.bloodbank.safety.IssueSafetyChecker.UnitFacts;
import org.openelisglobal.bloodbank.safety.PatientBloodProfile;
import org.openelisglobal.bloodbank.safety.PatientBloodProfile.CrossmatchResult;
import org.openelisglobal.bloodbank.safety.SafetyCheck;
import org.openelisglobal.bloodbank.service.BloodBankException.Kind;
import org.openelisglobal.bloodbank.service.BloodUnitTrail.Entry;
import org.openelisglobal.bloodbank.service.SecondPersonVerifier.Verification;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.EventType;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.ReservationStatus;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.UnitStatus;
import org.openelisglobal.bloodbank.valueholder.BloodUnit;
import org.openelisglobal.bloodbank.valueholder.BloodUnitEvent;
import org.openelisglobal.bloodbank.valueholder.BloodUnitReservation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class BloodIssueServiceImpl implements BloodIssueService {

    @Autowired
    private BloodUnitDAO unitDAO;

    @Autowired
    private BloodUnitReservationDAO reservationDAO;

    @Autowired
    private PatientBloodProfileService profileService;

    @Autowired
    private BloodBankSettingsService settingsService;

    @Autowired
    private SecondPersonVerifier secondPersonVerifier;

    @Autowired
    private BloodUnitTrail trail;

    private Clock clock = Clock.systemDefaultZone();

    void setClock(Clock clock) {
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CandidateUnit> candidates(Long patientId, Long productTypeId) {
        PatientBloodProfile profile = profileService.getProfile(patientId);
        BloodBankSettings settings = settingsService.get();
        Instant now = clock.instant();
        List<CandidateUnit> candidates = new ArrayList<>();
        for (BloodUnit unit : unitDAO.search(List.of(UnitStatus.AVAILABLE), productTypeId, null, null, null)) {
            List<SafetyCheck> checks = suitability(unit, profile, settings, now);
            candidates.add(new CandidateUnit(BloodBankMapper.unit(unit, now, settings.expiryWarnHours()),
                    BloodCompatibility.allowsReservation(checks), checks));
        }
        return candidates;
    }

    /** Group confirmation, ABO, RhD and expiry for one unit and one patient. */
    private static List<SafetyCheck> suitability(BloodUnit unit, PatientBloodProfile profile,
            BloodBankSettings settings, Instant now) {
        List<SafetyCheck> checks = new ArrayList<>(BloodCompatibility.check(unit.getProductType().getComponentClass(),
                unit.getAbo(), unit.getRhd(), profile.group(), settings.rules()));
        checks.add(IssueSafetyChecker.expiry(facts(unit), now));
        return checks;
    }

    @Override
    public ReservationView reserve(Long unitId, Long patientId, Long crossmatchResultId, String sysUserId) {
        Integer user = BloodUnitServiceImpl.userId(sysUserId);
        if (unitId == null || patientId == null) {
            throw new BloodBankException(Kind.INVALID, "bloodbank.reserve.error.fields");
        }
        BloodUnit unit = unitDAO.getForUpdate(unitId).orElseThrow(() -> BloodUnitServiceImpl.notFound(unitId));
        if (unit.getStatus() != UnitStatus.AVAILABLE) {
            throw new BloodBankException(Kind.CONFLICT, "bloodbank.reserve.error.notAvailable",
                    String.valueOf(unit.getStatus()));
        }
        PatientBloodProfile profile = profileService.getProfile(patientId);
        BloodBankSettings settings = settingsService.get();
        Instant now = clock.instant();
        List<SafetyCheck> checks = suitability(unit, profile, settings, now);
        if (!BloodCompatibility.allowsReservation(checks)) {
            throw new BloodBankException(Kind.UNSAFE, "bloodbank.reserve.error.unsuitable", checks);
        }
        CrossmatchResult crossmatch = null;
        if (unit.getProductType().needsCrossmatch()) {
            if (crossmatchResultId == null) {
                throw new BloodBankException(Kind.INVALID, "bloodbank.reserve.error.crossmatchRequired");
            }
            crossmatch = profile.crossmatch(crossmatchResultId);
            if (crossmatch == null) {
                throw new BloodBankException(Kind.UNSAFE, "bloodbank.reserve.error.crossmatchNotFound",
                        String.valueOf(crossmatchResultId));
            }
            if (!crossmatch.compatible()) {
                throw new BloodBankException(Kind.UNSAFE, "bloodbank.reserve.error.crossmatchIncompatible",
                        String.valueOf(crossmatch.value()));
            }
            if (crossmatch.validUntil() == null || !crossmatch.validUntil().isAfter(now)) {
                throw new BloodBankException(Kind.UNSAFE, "bloodbank.reserve.error.crossmatchExpired",
                        String.valueOf(crossmatch.validUntil()));
            }
            if (crossmatch.linkedUnitId() != null) {
                throw new BloodBankException(Kind.CONFLICT, "bloodbank.reserve.error.crossmatchInUse",
                        crossmatch.linkedDonationNumber());
            }
        }
        BloodUnitReservation reservation = new BloodUnitReservation();
        reservation.setUnit(unit);
        reservation.setPatientId(patientId);
        reservation.setCrossmatchResultId(crossmatch == null ? null : crossmatch.resultId());
        reservation.setCrossmatchValidUntil(crossmatch == null ? null : Timestamp.from(crossmatch.validUntil()));
        reservation.setStatus(ReservationStatus.ACTIVE);
        reservation.setReservedBy(user);
        reservation.setReservedAt(Timestamp.from(now));
        reservation.setSysUserId(String.valueOf(user));
        reservationDAO.insert(reservation);

        unit.setStatus(UnitStatus.RESERVED);
        unit.setPatientId(patientId);
        unit.setSysUserId(String.valueOf(user));
        unitDAO.update(unit);
        String details = crossmatch == null ? null
                : "crossmatch result " + crossmatch.resultId() + " (" + crossmatch.accessionNumber() + "), valid until "
                        + crossmatch.validUntil();
        trail.record(unit, EventType.RESERVED, user, now,
                new Entry(patientId, reservation.getId(), null, null, null, null, details));
        return BloodBankMapper.reservation(reservation);
    }

    @Override
    public ReservationView unreserve(Long reservationId, String reason, String sysUserId) {
        Integer user = BloodUnitServiceImpl.userId(sysUserId);
        BloodUnitServiceImpl.requireReason(reason);
        BloodUnitReservation reservation = reservationDAO.get(reservationId)
                .orElseThrow(() -> new BloodBankException(Kind.NOT_FOUND, "bloodbank.error.reservationNotFound",
                        String.valueOf(reservationId)));
        if (reservation.getStatus() != ReservationStatus.ACTIVE) {
            throw new BloodBankException(Kind.CONFLICT, "bloodbank.error.reservationNotActive",
                    String.valueOf(reservation.getStatus()));
        }
        BloodUnit unit = unitDAO.getForUpdate(reservation.getUnit().getId())
                .orElseThrow(() -> BloodUnitServiceImpl.notFound(reservation.getUnit().getId()));
        Instant now = clock.instant();
        reservation.setStatus(ReservationStatus.RELEASED);
        reservation.setClosedAt(Timestamp.from(now));
        reservation.setSysUserId(String.valueOf(user));
        reservationDAO.update(reservation);
        unit.setStatus(UnitStatus.AVAILABLE);
        unit.setPatientId(null);
        unit.setSysUserId(String.valueOf(user));
        unitDAO.update(unit);
        trail.record(unit, EventType.UNRESERVED, user, now,
                Entry.of(reservation.getPatientId(), reservation.getId(), reason));
        return BloodBankMapper.reservation(reservation);
    }

    @Override
    @Transactional(readOnly = true)
    public IssueCheck check(Long unitId, String patientIdentifier) {
        BloodUnit unit = unitDAO.get(unitId).orElseThrow(() -> BloodUnitServiceImpl.notFound(unitId));
        IssueSnapshot snapshot = snapshot(unit, patientIdentifier, SecondCheckOutcome.NOT_PROVIDED);
        List<SafetyCheck> checks = IssueSafetyChecker.check(snapshot, clock);
        return new IssueCheck(unitId, snapshot.scannedPatientId(), IssueSafetyChecker.nothingFailed(checks),
                IssueSafetyChecker.needsWarningReason(checks), checks);
    }

    @Override
    public UnitActionResult issue(Long unitId, IssueRequest request, String sysUserId) {
        Integer user = BloodUnitServiceImpl.userId(sysUserId);
        if (request == null || isBlank(request.patientIdentifier())) {
            throw new BloodBankException(Kind.INVALID, "bloodbank.issue.error.patientIdentifierRequired");
        }
        if (isBlank(request.location())) {
            throw new BloodBankException(Kind.INVALID, "bloodbank.issue.error.locationRequired");
        }
        if (isBlank(request.collectorName())) {
            throw new BloodBankException(Kind.INVALID, "bloodbank.issue.error.collectorRequired");
        }
        unitDAO.get(unitId).orElseThrow(() -> BloodUnitServiceImpl.notFound(unitId));
        // before the lock: this may ask the identity provider
        Verification second = secondPersonVerifier.verify(sysUserId, request.secondCheckerLogin(),
                request.secondCheckerPassword(), request.secondCheckerOtp());

        BloodUnit unit = unitDAO.getForUpdate(unitId).orElseThrow(() -> BloodUnitServiceImpl.notFound(unitId));
        IssueSnapshot snapshot = snapshot(unit, request.patientIdentifier(), second.outcome());
        List<SafetyCheck> checks = IssueSafetyChecker.check(snapshot, clock);
        if (!IssueSafetyChecker.allowsIssue(checks)) {
            throw new BloodBankException(Kind.UNSAFE, "bloodbank.issue.error.checksFailed", checks);
        }
        if (IssueSafetyChecker.needsWarningReason(checks) && isBlank(request.warningReason())) {
            throw new BloodBankException(Kind.UNSAFE, "bloodbank.issue.error.warningReasonRequired", checks);
        }
        Instant now = clock.instant();
        BloodUnitReservation reservation = reservationDAO.getForUnit(unitId, ReservationStatus.ACTIVE)
                .orElseThrow(() -> new BloodBankException(Kind.CONFLICT, "bloodbank.check.patient.noReservation"));
        reservation.setStatus(ReservationStatus.ISSUED);
        reservation.setIssuedAt(Timestamp.from(now));
        reservation.setSysUserId(String.valueOf(user));
        reservationDAO.update(reservation);
        unit.setStatus(UnitStatus.ISSUED);
        unit.setSysUserId(String.valueOf(user));
        unitDAO.update(unit);
        String summary = checks.stream().map(c -> c.code() + "=" + c.status()).collect(Collectors.joining(", "));
        BloodUnitEvent event = trail.record(unit, EventType.ISSUED, user, now,
                new Entry(reservation.getPatientId(), reservation.getId(), second.verifiedUserId(),
                        request.warningReason(), request.location(), request.collectorName(), "checks: " + summary));
        return result(unit, now, List.of(event));
    }

    @Override
    public UnitActionResult returnUnit(Long unitId, String reason, String sysUserId) {
        Integer user = BloodUnitServiceImpl.userId(sysUserId);
        BloodUnit unit = issuedUnit(unitId);
        BloodUnitReservation reservation = issuedReservation(unitId);
        Instant now = clock.instant();
        int window = settingsService.get().returnWindowMinutes();
        Instant issuedAt = reservation.getIssuedAt() == null ? now : reservation.getIssuedAt().toInstant();
        long minutesOut = Duration.between(issuedAt, now).toMinutes();
        boolean inTime = !now.isAfter(issuedAt.plus(Duration.ofMinutes(window)));

        reservation.setStatus(ReservationStatus.RELEASED);
        reservation.setClosedAt(Timestamp.from(now));
        reservation.setSysUserId(String.valueOf(user));
        reservationDAO.update(reservation);
        List<BloodUnitEvent> events = new ArrayList<>();
        events.add(trail.record(unit, EventType.RETURNED, user, now,
                new Entry(reservation.getPatientId(), reservation.getId(), null, reason, null, null,
                        "returned " + minutesOut + " min after issue; window " + window + " min")));
        unit.setPatientId(null);
        unit.setSysUserId(String.valueOf(user));
        if (inTime) {
            unit.setStatus(UnitStatus.AVAILABLE);
        } else {
            unit.setStatus(UnitStatus.DISCARDED);
            events.add(trail.record(unit, EventType.DISCARDED, user, now, new Entry(reservation.getPatientId(),
                    reservation.getId(), null, reason, null, null, "returned after the return window")));
        }
        unitDAO.update(unit);
        return result(unit, now, events);
    }

    @Override
    public UnitActionResult transfused(Long unitId, String reactionNote, String sysUserId) {
        Integer user = BloodUnitServiceImpl.userId(sysUserId);
        if (reactionNote != null && reactionNote.trim().length() > 500) {
            throw new BloodBankException(Kind.INVALID, "bloodbank.error.reasonTooLong", "500");
        }
        BloodUnit unit = issuedUnit(unitId);
        BloodUnitReservation reservation = issuedReservation(unitId);
        Instant now = clock.instant();
        reservation.setClosedAt(Timestamp.from(now));
        reservation.setSysUserId(String.valueOf(user));
        reservationDAO.update(reservation);
        unit.setStatus(UnitStatus.TRANSFUSED);
        unit.setSysUserId(String.valueOf(user));
        unitDAO.update(unit);
        List<BloodUnitEvent> events = new ArrayList<>();
        events.add(trail.record(unit, EventType.TRANSFUSED, user, now,
                Entry.of(reservation.getPatientId(), reservation.getId(), null)));
        if (!isBlank(reactionNote)) {
            events.add(trail.record(unit, EventType.REACTION, user, now,
                    Entry.of(reservation.getPatientId(), reservation.getId(), reactionNote)));
        }
        return result(unit, now, events);
    }

    private BloodUnit issuedUnit(Long unitId) {
        BloodUnit unit = unitDAO.getForUpdate(unitId).orElseThrow(() -> BloodUnitServiceImpl.notFound(unitId));
        if (unit.getStatus() != UnitStatus.ISSUED) {
            throw new BloodBankException(Kind.CONFLICT, "bloodbank.error.notIssued", String.valueOf(unit.getStatus()));
        }
        return unit;
    }

    private BloodUnitReservation issuedReservation(Long unitId) {
        return reservationDAO.getForUnit(unitId, ReservationStatus.ISSUED)
                .orElseThrow(() -> new BloodBankException(Kind.CONFLICT, "bloodbank.error.notIssued", "ISSUED"));
    }

    private IssueSnapshot snapshot(BloodUnit unit, String patientIdentifier, SecondCheckOutcome secondCheck) {
        BloodUnitReservation reservation = reservationDAO.getForUnit(unit.getId(), ReservationStatus.ACTIVE)
                .orElse(null);
        Long scannedPatientId = profileService.resolvePatientIdentifier(patientIdentifier);
        PatientBloodProfile profile = reservation == null ? null
                : profileService.getProfile(reservation.getPatientId());
        ReservationFacts reservationFacts = reservation == null ? null
                : new ReservationFacts(reservation.getId(), reservation.getPatientId(), reservation.getStatus(),
                        reservation.getCrossmatchResultId());
        return new IssueSnapshot(facts(unit), reservationFacts, scannedPatientId, profile,
                settingsService.get().rules(), secondCheck);
    }

    private static UnitFacts facts(BloodUnit unit) {
        return new UnitFacts(unit.getId(), unit.getStatus(), unit.getProductType().getComponentClass(),
                unit.getProductType().needsCrossmatch(), unit.getAbo(), unit.getRhd(),
                BloodBankMapper.instant(unit.getExpiresAt()), unit.getPatientId());
    }

    private UnitActionResult result(BloodUnit unit, Instant now, List<BloodUnitEvent> events) {
        Function<Integer, String> names = trail.userNames();
        return new UnitActionResult(BloodBankMapper.unit(unit, now, settingsService.get().expiryWarnHours()),
                events.stream().map(e -> BloodBankMapper.event(e, names)).toList());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
