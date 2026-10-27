package org.openelisglobal.bloodbank.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.openelisglobal.bloodbank.dao.BloodProductTypeDAO;
import org.openelisglobal.bloodbank.dao.BloodUnitDAO;
import org.openelisglobal.bloodbank.dao.BloodUnitEventDAO;
import org.openelisglobal.bloodbank.dao.BloodUnitReservationDAO;
import org.openelisglobal.bloodbank.form.BloodBankForms.ReceiveLine;
import org.openelisglobal.bloodbank.form.BloodBankViews.LineError;
import org.openelisglobal.bloodbank.form.BloodBankViews.ParsedLabel;
import org.openelisglobal.bloodbank.form.BloodBankViews.ReceiveLineResult;
import org.openelisglobal.bloodbank.form.BloodBankViews.UnitActionResult;
import org.openelisglobal.bloodbank.form.BloodBankViews.UnitView;
import org.openelisglobal.bloodbank.isbt.Isbt128Parser;
import org.openelisglobal.bloodbank.service.BloodBankException.Kind;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.Abo;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.ComponentClass;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.EventType;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.ReservationStatus;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.RhD;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.UnitStatus;
import org.openelisglobal.bloodbank.valueholder.BloodProductType;
import org.openelisglobal.bloodbank.valueholder.BloodUnit;
import org.openelisglobal.bloodbank.valueholder.BloodUnitReservation;
import org.openelisglobal.systemuser.service.SystemUserService;
import org.springframework.test.util.ReflectionTestUtils;

@RunWith(MockitoJUnitRunner.class)
public class BloodUnitServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-27T14:00:00Z");
    private static final String USER = "10";
    private static final String DIN = "G123498654321";
    private static final String CODE = "E0291V00";

    @Mock
    private BloodUnitDAO unitDAO;
    @Mock
    private BloodProductTypeDAO typeDAO;
    @Mock
    private BloodUnitReservationDAO reservationDAO;
    @Mock
    private BloodUnitEventDAO eventDAO;
    @Mock
    private BloodProductCatalogService catalogService;
    @Mock
    private BloodBankSettingsService settingsService;
    @Mock
    private SystemUserService systemUserService;

    @InjectMocks
    private BloodUnitServiceImpl service;

    private BloodProductType redCells;

    @Before
    public void setUp() {
        BloodUnitTrail trail = new BloodUnitTrail();
        ReflectionTestUtils.setField(trail, "eventDAO", eventDAO);
        ReflectionTestUtils.setField(trail, "systemUserService", systemUserService);
        ReflectionTestUtils.setField(service, "trail", trail);
        service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(settingsService.get()).thenReturn(BloodBankSettings.DEFAULTS);
        redCells = new BloodProductType();
        redCells.setId(1L);
        redCells.setCode("RED_CELLS");
        redCells.setName("Red cells");
        redCells.setComponentClass(ComponentClass.RED_CELLS);
        lenient().when(catalogService.resolve(CODE)).thenReturn(Optional.of(redCells));
    }

    private static ReceiveLine typed(String din, String code, String group, String expires) {
        return new ReceiveLine(null, din, code, group, expires, null, "Example Blood Service", 280);
    }

    private static List<String> codes(ReceiveLineResult result) {
        return result.errors().stream().map(LineError::code).toList();
    }

    private static BloodBankException expectRefusal(Runnable call) {
        try {
            call.run();
        } catch (BloodBankException e) {
            return e;
        }
        fail("expected a refusal");
        return null;
    }

    @Test
    public void aGoodLineIsReceivedAsAvailableWithItsTrailEntry() {
        when(unitDAO.getByDonationNumberAndProductCode(DIN, CODE)).thenReturn(Optional.empty());

        List<ReceiveLineResult> results = service.receive(List.of(new ReceiveLine(
                List.of("=" + DIN + "00", "=%9500", "=<" + CODE, "&>0263061430"), null, null, null, null,
                "2026-10-20T09:15", "Example Blood Service", 280)), USER);

        assertTrue(results.get(0).received());
        verify(unitDAO).insert(argThat(u -> DIN.equals(u.getDonationNumber()) && CODE.equals(u.getProductCode())
                && u.getAbo() == Abo.O && u.getRhd() == RhD.NEG && u.getStatus() == UnitStatus.AVAILABLE
                && Timestamp.from(Instant.parse("2026-11-02T14:30:00Z")).equals(u.getExpiresAt())
                && Timestamp.from(Instant.parse("2026-10-20T09:15:00Z")).equals(u.getCollectedAt())
                && Integer.valueOf(10).equals(u.getReceivedBy()) && u.getProductType() == redCells));
        verify(eventDAO).append(argThat(e -> e.getEventType() == EventType.RECEIVED
                && Integer.valueOf(10).equals(e.getPerformedBy()) && Timestamp.from(NOW).equals(e.getOccurredAt())));
    }

    @Test
    public void eachLineGetsItsOwnResultAndOnlyGoodLinesAreSaved() {
        String otherDin = "W123412345678";
        when(unitDAO.getByDonationNumberAndProductCode(DIN, CODE)).thenReturn(Optional.empty());
        when(unitDAO.getByDonationNumberAndProductCode(otherDin, CODE)).thenReturn(Optional.of(new BloodUnit()));
        when(catalogService.resolve("E9999V00")).thenReturn(Optional.empty());

        List<ReceiveLineResult> results = service.receive(List.of( //
                typed(DIN, CODE, "O NEG", "2026-11-30"), // good
                typed(DIN, CODE, "O NEG", "2026-11-30"), // same unit twice in this delivery
                typed(otherDin, CODE, "A POS", "2026-11-30"), // already in stock
                typed("W123412345679", "E9999V00", "A POS", "2026-11-30"), // product code not mapped
                typed("W123412345670", CODE, "C POS", "2026-11-30"), // not a group
                typed("W123412345671", CODE, "B NEG", "2026-10-27T14:00"), // expires this minute
                new ReceiveLine(null, null, null, null, null, null, null, null)), USER);

        assertEquals(List.of(true, false, false, false, false, false, false),
                results.stream().map(ReceiveLineResult::received).toList());
        assertEquals(List.of(BloodUnitServiceImpl.ERR_DUPLICATE), codes(results.get(1)));
        assertEquals(List.of(BloodUnitServiceImpl.ERR_DUPLICATE), codes(results.get(2)));
        assertEquals(List.of(BloodUnitServiceImpl.ERR_UNKNOWN_PRODUCT), codes(results.get(3)));
        assertEquals(List.of("E9999V00"), results.get(3).errors().get(0).args());
        assertEquals(List.of(Isbt128Parser.ERR_GROUP), codes(results.get(4)));
        assertEquals(List.of(BloodUnitServiceImpl.ERR_EXPIRED), codes(results.get(5)));
        assertEquals(4, codes(results.get(6)).size());
        assertTrue(codes(results.get(6)).stream().allMatch(BloodUnitServiceImpl.ERR_MISSING::equals));

        ArgumentCaptor<BloodUnit> saved = ArgumentCaptor.forClass(BloodUnit.class);
        verify(unitDAO).insert(saved.capture());
        assertEquals(DIN, saved.getValue().getDonationNumber());
    }

    private BloodUnit received(String productCode, Abo abo, RhD rhd) {
        BloodUnit unit = new BloodUnit();
        unit.setId(2L);
        unit.setDonationNumber(DIN);
        unit.setProductCode(productCode);
        unit.setProductType(redCells);
        unit.setAbo(abo);
        unit.setRhd(rhd);
        unit.setStatus(UnitStatus.AVAILABLE);
        return unit;
    }

    @Test
    public void aDonationAlreadyReceivedWithAnotherGroupIsRefusedWhateverTheProductCode() {
        // the owner's retest: V00 in stock as O NEG, the V01 split typed as A POS
        String split = "E0291V01";
        when(catalogService.resolve(split)).thenReturn(Optional.of(redCells));
        when(unitDAO.getByDonationNumberAndProductCode(DIN, split)).thenReturn(Optional.empty());
        when(unitDAO.getByDonationNumber(DIN)).thenReturn(List.of(received(CODE, Abo.O, RhD.NEG)));

        List<ReceiveLineResult> results = service.receive(
                List.of(typed(DIN, split, "A POS", "2026-11-30"), typed(DIN, split, "O POS", "2026-11-30")), USER);

        assertEquals(List.of(false, false), results.stream().map(ReceiveLineResult::received).toList());
        assertEquals(List.of(BloodUnitServiceImpl.ERR_GROUP_CONFLICT), codes(results.get(0)));
        assertEquals(List.of(DIN, "O", "NEG"), results.get(0).errors().get(0).args());
        // RhD alone differing is refused too
        assertTrue(codes(results.get(1)).contains(BloodUnitServiceImpl.ERR_GROUP_CONFLICT));
        verify(unitDAO, never()).insert(ArgumentCaptor.forClass(BloodUnit.class).capture());
    }

    @Test
    public void anotherProductOfTheSameDonationWithTheSameGroupIsReceived() {
        String plasma = "E0869V00";
        when(catalogService.resolve(plasma)).thenReturn(Optional.of(redCells));
        when(unitDAO.getByDonationNumberAndProductCode(DIN, plasma)).thenReturn(Optional.empty());
        when(unitDAO.getByDonationNumber(DIN)).thenReturn(List.of(received(CODE, Abo.O, RhD.NEG)));

        List<ReceiveLineResult> results = service.receive(List.of(typed(DIN, plasma, "O NEG", "2026-11-30")), USER);

        assertTrue(results.get(0).received());
        verify(unitDAO).insert(argThat(u -> plasma.equals(u.getProductCode()) && u.getAbo() == Abo.O));
    }

    @Test
    public void theSameDonationAndProductWithTheSameGroupIsStillADuplicate() {
        when(unitDAO.getByDonationNumberAndProductCode(DIN, CODE))
                .thenReturn(Optional.of(received(CODE, Abo.O, RhD.NEG)));
        when(unitDAO.getByDonationNumber(DIN)).thenReturn(List.of(received(CODE, Abo.O, RhD.NEG)));

        List<ReceiveLineResult> results = service.receive(List.of(typed(DIN, CODE, "O NEG", "2026-11-30")), USER);

        assertEquals(List.of(BloodUnitServiceImpl.ERR_DUPLICATE), codes(results.get(0)));
        verify(unitDAO, never()).insert(ArgumentCaptor.forClass(BloodUnit.class).capture());
    }

    @Test
    public void twoProductsOfOneDonationInOneDeliveryMustShareTheGroup() {
        String split = "E0291V01";
        when(catalogService.resolve(split)).thenReturn(Optional.of(redCells));
        when(unitDAO.getByDonationNumberAndProductCode(DIN, CODE)).thenReturn(Optional.empty());
        when(unitDAO.getByDonationNumberAndProductCode(DIN, split)).thenReturn(Optional.empty());

        List<ReceiveLineResult> results = service.receive(
                List.of(typed(DIN, CODE, "O NEG", "2026-11-30"), typed(DIN, split, "B POS", "2026-11-30")), USER);

        assertTrue(results.get(0).received());
        assertEquals(List.of(BloodUnitServiceImpl.ERR_GROUP_CONFLICT), codes(results.get(1)));
        assertEquals(List.of(DIN, "O", "NEG"), results.get(1).errors().get(0).args());
    }

    @Test
    public void aUnitExpiringAMinuteFromNowIsStillReceived() {
        when(unitDAO.getByDonationNumberAndProductCode(DIN, CODE)).thenReturn(Optional.empty());

        List<ReceiveLineResult> results = service.receive(List.of(typed(DIN, CODE, "AB+", "2026-10-27T14:01")), USER);

        assertTrue(results.get(0).received());
    }

    @Test
    public void aWrongCheckCharacterIsRefused() {
        List<ReceiveLineResult> results = service.receive(List.of(typed(DIN + "K", CODE, "O NEG", "2026-11-30")), USER);

        assertEquals(List.of(Isbt128Parser.ERR_DIN_CHECK), codes(results.get(0)));
        verify(unitDAO, never()).insert(ArgumentCaptor.forClass(BloodUnit.class).capture());
    }

    @Test
    public void aDeliveryNeedsLinesAndNotTooMany() {
        assertEquals(Kind.INVALID, expectRefusal(() -> service.receive(List.of(), USER)).getKind());
        assertEquals(Kind.INVALID, expectRefusal(() -> service.receive(null, USER)).getKind());
        List<ReceiveLine> tooMany = new ArrayList<>();
        for (int i = 0; i <= BloodUnitServiceImpl.MAX_LINES; i++) {
            tooMany.add(typed(DIN, CODE, "O NEG", "2026-11-30"));
        }
        assertEquals("bloodbank.receive.error.tooManyLines",
                expectRefusal(() -> service.receive(tooMany, USER)).getErrorCode());
        verifyZeroInteractions(unitDAO, eventDAO);
    }

    @Test
    public void parseNamesAnUnmappedProductCode() {
        when(catalogService.resolve("E9999V00")).thenReturn(Optional.empty());

        ParsedLabel label = service.parse(List.of("=<E9999V00", "=%6200"));

        assertEquals(Abo.A, label.abo());
        assertEquals(List.of(BloodUnitServiceImpl.ERR_UNKNOWN_PRODUCT),
                label.errors().stream().map(LineError::code).toList());
        assertEquals(List.of("E9999V00"), label.errors().get(0).args());
        verifyZeroInteractions(unitDAO, eventDAO);
    }

    private BloodUnit stocked(UnitStatus status) {
        BloodUnit unit = new BloodUnit();
        unit.setId(5L);
        unit.setDonationNumber(DIN);
        unit.setProductCode(CODE);
        unit.setProductType(redCells);
        unit.setAbo(Abo.O);
        unit.setRhd(RhD.NEG);
        unit.setStatus(status);
        unit.setExpiresAt(Timestamp.from(NOW.plus(Duration.ofDays(3))));
        unit.setReceivedAt(Timestamp.from(NOW.minus(Duration.ofDays(3))));
        return unit;
    }

    @Test
    public void quarantineNeedsAReason() {
        assertEquals("bloodbank.error.reasonRequired",
                expectRefusal(() -> service.quarantine(5L, " ", USER)).getErrorCode());
        verifyZeroInteractions(unitDAO, eventDAO);
    }

    @Test
    public void quarantineMovesAnAvailableUnitAndRecordsWhy() {
        when(unitDAO.getForUpdate(5L)).thenReturn(Optional.of(stocked(UnitStatus.AVAILABLE)));
        when(reservationDAO.getForUnit(5L, ReservationStatus.ACTIVE)).thenReturn(Optional.empty());

        UnitActionResult result = service.quarantine(5L, "bag leaking at the port", USER);

        assertEquals(UnitStatus.QUARANTINED, result.unit().status());
        verify(eventDAO).append(argThat(e -> e.getEventType() == EventType.QUARANTINED
                && "bag leaking at the port".equals(e.getReason())));
    }

    @Test
    public void releaseIsOnlyFromQuarantine() {
        when(unitDAO.getForUpdate(5L)).thenReturn(Optional.of(stocked(UnitStatus.AVAILABLE)));

        assertEquals("bloodbank.error.notQuarantined", expectRefusal(() -> service.release(5L, null, USER))
                .getErrorCode());
        verifyZeroInteractions(eventDAO);
    }

    @Test
    public void discardingAReservedUnitReleasesTheReservationFirst() {
        BloodUnit unit = stocked(UnitStatus.RESERVED);
        unit.setPatientId(501L);
        BloodUnitReservation reservation = new BloodUnitReservation();
        reservation.setId(70L);
        reservation.setUnit(unit);
        reservation.setPatientId(501L);
        reservation.setStatus(ReservationStatus.ACTIVE);
        when(unitDAO.getForUpdate(5L)).thenReturn(Optional.of(unit));
        when(reservationDAO.getForUnit(5L, ReservationStatus.ACTIVE)).thenReturn(Optional.of(reservation));

        UnitActionResult result = service.discard(5L, "dropped on the floor", USER);

        assertEquals(UnitStatus.DISCARDED, result.unit().status());
        assertEquals(null, result.unit().patientId());
        assertEquals(List.of(EventType.UNRESERVED, EventType.DISCARDED),
                result.events().stream().map(e -> e.eventType()).toList());
        verify(reservationDAO).update(argThat(r -> r.getStatus() == ReservationStatus.RELEASED));
    }

    @Test
    public void anIssuedOrFinalUnitCannotBeDiscardedFromTheShelf() {
        for (UnitStatus status : List.of(UnitStatus.ISSUED, UnitStatus.TRANSFUSED, UnitStatus.DISCARDED,
                UnitStatus.RETURNED_TO_SUPPLIER)) {
            when(unitDAO.getForUpdate(5L)).thenReturn(Optional.of(stocked(status)));
            assertEquals(status.name(), Kind.CONFLICT,
                    expectRefusal(() -> service.discard(5L, "test", USER)).getKind());
            assertEquals(status.name(), Kind.CONFLICT,
                    expectRefusal(() -> service.returnToSupplier(5L, "test", USER)).getKind());
        }
        verifyZeroInteractions(eventDAO);
    }

    @Test
    public void anUnknownUnitIsNotFound() {
        when(unitDAO.getForUpdate(99L)).thenReturn(Optional.empty());
        assertEquals(Kind.NOT_FOUND, expectRefusal(() -> service.discard(99L, "x", USER)).getKind());
    }

    @Test
    public void searchFiltersByPatientAndFlagsExpiry() {
        BloodUnit mine = stocked(UnitStatus.RESERVED);
        mine.setPatientId(501L);
        BloodUnit someoneElses = stocked(UnitStatus.RESERVED);
        someoneElses.setId(6L);
        someoneElses.setPatientId(502L);
        someoneElses.setExpiresAt(Timestamp.from(NOW.plus(Duration.ofHours(47))));
        when(unitDAO.search(List.of(UnitStatus.RESERVED), null, null, null, null))
                .thenReturn(List.of(mine, someoneElses));

        List<UnitView> forPatient = service.search(List.of(UnitStatus.RESERVED), null, null, null, null, 501L);
        List<UnitView> all = service.search(List.of(UnitStatus.RESERVED), null, null, null, null, null);

        assertEquals(List.of(5L), forPatient.stream().map(UnitView::id).toList());
        assertEquals(2, all.size());
        assertFalse(all.get(0).expiringSoon());
        assertTrue(all.get(1).expiringSoon());
    }

    @Test
    public void anIssuedUnitCarriesItsIssueTimeAndTheReturnWindow() {
        BloodUnit issued = stocked(UnitStatus.ISSUED);
        BloodUnit available = stocked(UnitStatus.AVAILABLE);
        available.setId(6L);
        BloodUnitReservation reservation = new BloodUnitReservation();
        reservation.setUnit(issued);
        reservation.setStatus(ReservationStatus.ISSUED);
        reservation.setIssuedAt(Timestamp.from(NOW.minus(Duration.ofMinutes(107))));
        when(unitDAO.search(List.of(UnitStatus.ISSUED, UnitStatus.AVAILABLE), null, null, null, null))
                .thenReturn(List.of(issued, available));
        when(reservationDAO.getForUnit(5L, ReservationStatus.ISSUED)).thenReturn(Optional.of(reservation));

        List<UnitView> views = service.search(List.of(UnitStatus.ISSUED, UnitStatus.AVAILABLE), null, null, null, null,
                null);

        assertEquals("2026-10-27T12:13:00Z", views.get(0).issuedAt());
        assertEquals(Integer.valueOf(BloodBankSettings.DEFAULTS.returnWindowMinutes()),
                views.get(0).returnWindowMinutes());
        // only issued units are looked up, and only they carry the window
        assertEquals(null, views.get(1).issuedAt());
        assertEquals(null, views.get(1).returnWindowMinutes());
        verify(reservationDAO, never()).getForUnit(6L, ReservationStatus.ISSUED);
    }

    @Test
    public void expiringWithinHoursBecomesAnUpperBoundOnExpiry() {
        when(unitDAO.search(List.of(), null, Abo.O, RhD.NEG, Timestamp.from(NOW.plus(Duration.ofHours(48)))))
                .thenReturn(List.of(stocked(UnitStatus.AVAILABLE)));

        assertEquals(1, service.search(List.of(), null, Abo.O, RhD.NEG, 48, null).size());
        assertEquals(Kind.INVALID, expectRefusal(() -> service.search(null, null, null, null, -1, null)).getKind());
    }
}
