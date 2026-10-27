package org.openelisglobal.bloodbank.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openelisglobal.BaseWebContextSensitiveTest;
import org.openelisglobal.bloodbank.dao.BloodProductTypeDAO;
import org.openelisglobal.bloodbank.dao.BloodUnitDAO;
import org.openelisglobal.bloodbank.dao.BloodUnitReservationDAO;
import org.openelisglobal.bloodbank.form.BloodBankForms.IssueRequest;
import org.openelisglobal.bloodbank.form.BloodBankForms.ProductCodeForm;
import org.openelisglobal.bloodbank.form.BloodBankForms.ReceiveLine;
import org.openelisglobal.bloodbank.form.BloodBankViews.EventView;
import org.openelisglobal.bloodbank.form.BloodBankViews.IssueCheck;
import org.openelisglobal.bloodbank.form.BloodBankViews.ProductTypeView;
import org.openelisglobal.bloodbank.form.BloodBankViews.ReceiveLineResult;
import org.openelisglobal.bloodbank.form.BloodBankViews.ReservationView;
import org.openelisglobal.bloodbank.form.BloodBankViews.UnitActionResult;
import org.openelisglobal.bloodbank.safety.IssueSafetyChecker.SecondCheckOutcome;
import org.openelisglobal.bloodbank.safety.PatientBloodProfile;
import org.openelisglobal.bloodbank.safety.PatientBloodProfile.CrossmatchResult;
import org.openelisglobal.bloodbank.safety.SecondCheckMode;
import org.openelisglobal.bloodbank.service.SecondPersonVerifier.Verification;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.Abo;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.EventType;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.ReservationStatus;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.RhD;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.UnitStatus;
import org.openelisglobal.bloodbank.valueholder.BloodUnit;
import org.openelisglobal.bloodbank.valueholder.BloodUnitReservation;
import org.openelisglobal.common.services.IStatusService;
import org.openelisglobal.common.services.StatusService.AnalysisStatus;
import org.openelisglobal.common.services.StatusService.SampleStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Against a real database built by Liquibase: the profile query over our own
 * patients, samples and results; the database constraints; the append-only
 * trail; and one unit from receipt to return. Every row is created here with
 * unique identifiers, and every read is scoped to them. The clock is pinned.
 */
public class BloodBankDatabaseIntegrationTest extends BaseWebContextSensitiveTest {

    private static final Instant NOW = Instant.parse("2026-10-27T14:00:00Z");

    @Autowired
    private PatientBloodProfileService profileService;
    @Autowired
    private BloodBankSettingsService settingsService;
    @Autowired
    private BloodUnitService unitService;
    @Autowired
    private BloodIssueService issueService;
    @Autowired
    private BloodTraceService traceService;
    @Autowired
    private BloodProductCatalogService catalogService;
    @Autowired
    private BloodUnitDAO unitDAO;
    @Autowired
    private BloodProductTypeDAO typeDAO;
    @Autowired
    private BloodUnitReservationDAO reservationDAO;
    @Autowired
    private IStatusService statusService;

    private String suffix;
    private BloodBankSettings savedSettings;
    private String finalized;
    private String notStarted;
    private long testAbo;
    private long testRhd;
    private long testDat;
    private long testCrossmatch;
    private long dictA;
    private long dictB;
    private long dictPositive;
    private long dictCompatible;

    @Before
    public void createOwnCatalogue() throws Exception {
        super.setUp();
        suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        finalized = statusService.getStatusID(AnalysisStatus.Finalized);
        notStarted = statusService.getStatusID(AnalysisStatus.NotStarted);
        testAbo = test("BBABO" + suffix);
        testRhd = test("BBRHD" + suffix);
        testDat = test("BBDAT" + suffix);
        testCrossmatch = test("BBXM" + suffix);
        dictA = dictionary("A");
        dictB = dictionary("B");
        dictPositive = dictionary("Positive");
        dictCompatible = dictionary("Compatible");

        // the settings are shared reference data: point them at this test's tests, put
        // them back after
        savedSettings = settingsService.get();
        Map<String, Object> mine = new LinkedHashMap<>();
        mine.put("testAbo", "BBABO" + suffix);
        mine.put("testRhd", "BBRHD" + suffix);
        mine.put("testDat", "BBDAT" + suffix);
        mine.put("testCrossmatch", "BBXM" + suffix);
        mine.put("testAntibodyScreen", "");
        mine.put("crossmatchCompatibleValues", "Compatible");
        mine.put("crossmatchValidHours", 72);
        mine.put("returnWindowMinutes", 30);
        mine.put("twoSampleGroup", true);
        settingsService.update(mine, TEST_SYS_USER_ID);

        Clock clock = Clock.fixed(NOW, ZoneId.systemDefault());
        target(unitService, BloodUnitServiceImpl.class).setClock(clock);
        target(issueService, BloodIssueServiceImpl.class).setClock(clock);
    }

    @After
    public void restoreSharedState() {
        Map<String, Object> back = new LinkedHashMap<>();
        back.put("testAbo", savedSettings.testAbo());
        back.put("testRhd", savedSettings.testRhd());
        back.put("testDat", savedSettings.testDat());
        back.put("testCrossmatch", savedSettings.testCrossmatch());
        back.put("testAntibodyScreen", savedSettings.testAntibodyScreen());
        back.put("crossmatchCompatibleValues", String.join(",", savedSettings.crossmatchCompatibleValues()));
        back.put("crossmatchValidHours", savedSettings.crossmatchValidHours());
        back.put("returnWindowMinutes", savedSettings.returnWindowMinutes());
        back.put("twoSampleGroup", savedSettings.twoSampleGroup());
        settingsService.update(back, TEST_SYS_USER_ID);
        target(unitService, BloodUnitServiceImpl.class).setClock(Clock.systemDefaultZone());
        target(issueService, BloodIssueServiceImpl.class).setClock(Clock.systemDefaultZone());
    }

    private static <T> T target(Object proxy, Class<T> type) {
        return type.cast(AopTestUtils.getTargetObject(proxy));
    }

    // ------------------------------------------------------------------ own rows

    private long nextId(String sequence) {
        return jdbcTemplate.queryForObject("SELECT nextval('" + sequence + "')", Long.class);
    }

    private long test(String localCode) {
        long id = nextId("test_seq");
        jdbcTemplate.update(
                "INSERT INTO test (id, name, description, guid, local_code, is_active, lastupdated)"
                        + " VALUES (?, ?, ?, ?, ?, 'Y', now())",
                id, localCode, localCode, UUID.randomUUID().toString(), localCode);
        return id;
    }

    private long dictionary(String entry) {
        long id = nextId("dictionary_seq");
        jdbcTemplate.update("INSERT INTO dictionary (id, dict_entry, is_active, lastupdated) VALUES (?, ?, 'Y', now())",
                id, entry);
        return id;
    }

    private long patient(String nationalId) {
        long person = nextId("person_seq");
        jdbcTemplate.update("INSERT INTO person (id, last_name, first_name, lastupdated) VALUES (?, ?, ?, now())",
                person, "Fictional" + suffix, "Bloodbank");
        long patient = nextId("patient_seq");
        jdbcTemplate.update("INSERT INTO patient (id, person_id, gender, national_id, external_id, lastupdated)"
                + " VALUES (?, ?, 'F', ?, ?, now())", patient, person, nationalId, nationalId + "-EXT");
        return patient;
    }

    /**
     * A sample of the patient collected at that time; returns {sampleId,
     * sampleItemId}.
     */
    private long[] sample(long patientId, String accession, Timestamp collected) {
        long sample = nextId("sample_seq");
        jdbcTemplate.update(
                "INSERT INTO sample (id, accession_number, entered_date, received_date, collection_date,"
                        + " lastupdated) VALUES (?, ?, ?, ?, ?, now())",
                sample, accession, collected, collected, collected);
        jdbcTemplate.update("INSERT INTO sample_human (id, samp_id, patient_id, lastupdated) VALUES (?, ?, ?, now())",
                nextId("sample_human_seq"), sample, patientId);
        long item = nextId("sample_item_seq");
        jdbcTemplate.update(
                "INSERT INTO sample_item (id, samp_id, sort_order, status_id, lastupdated)"
                        + " VALUES (?, ?, 1, ?, now())",
                item, sample, Long.valueOf(statusService.getStatusID(SampleStatus.Entered)));
        return new long[] { sample, item };
    }

    /**
     * A sample as the order form saves it: the collection time on the sample item
     * only. Returns {sampleId, sampleItemId}.
     */
    private long[] orderFormSample(long patientId, String accession, Timestamp collected) {
        long[] ids = sample(patientId, accession, collected);
        jdbcTemplate.update("UPDATE sample SET collection_date = NULL WHERE id = ?", ids[0]);
        jdbcTemplate.update("UPDATE sample_item SET collection_date = ? WHERE id = ?", collected, ids[1]);
        return ids;
    }

    /** A result on its own analysis; returns the result id. */
    private long result(long sampleItem, long testId, String analysisStatus, String type, String value) {
        long analysis = nextId("analysis_seq");
        jdbcTemplate.update(
                "INSERT INTO analysis (id, sampitem_id, test_id, status_id, analysis_type, lastupdated)"
                        + " VALUES (?, ?, ?, ?, 'MANUAL', now())",
                analysis, sampleItem, testId, Long.valueOf(analysisStatus));
        long result = nextId("result_seq");
        jdbcTemplate.update(
                "INSERT INTO result (id, analysis_id, value, result_type, lastupdated)" + " VALUES (?, ?, ?, ?, now())",
                result, analysis, value, type);
        return result;
    }

    private String accession() {
        return "BB" + ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L);
    }

    /**
     * A patient grouped A POS on two samples, with a compatible crossmatch on the
     * second.
     */
    private record Grouped(long patientId, String lastAccession, long crossmatchResultId, Timestamp collected) {
    }

    private Grouped groupedPatient() {
        long patient = patient("BBNID" + suffix + ThreadLocalRandom.current().nextInt(1000));
        Timestamp first = Timestamp.from(NOW.minus(Duration.ofDays(3)));
        Timestamp second = Timestamp.from(NOW.minus(Duration.ofHours(20)));
        long[] s1 = sample(patient, accession(), first);
        result(s1[1], testAbo, finalized, "D", String.valueOf(dictA));
        result(s1[1], testRhd, finalized, "D", String.valueOf(dictPositive));
        String lastAccession = accession();
        long[] s2 = sample(patient, lastAccession, second);
        result(s2[1], testAbo, finalized, "D", String.valueOf(dictA));
        result(s2[1], testRhd, finalized, "D", String.valueOf(dictPositive));
        result(s2[1], testDat, finalized, "A", "Negative");
        long crossmatch = result(s2[1], testCrossmatch, finalized, "D", String.valueOf(dictCompatible));
        return new Grouped(patient, lastAccession, crossmatch, second);
    }

    // ------------------------------------------------------------------ profile

    @Test
    public void theProfileReadsOnlyThisPatientsValidatedResults() {
        Grouped grouped = groupedPatient();
        // a result not yet validated must not count, even though it disagrees
        long[] pending = sample(grouped.patientId(), accession(), Timestamp.from(NOW.minus(Duration.ofHours(1))));
        result(pending[1], testAbo, notStarted, "D", String.valueOf(dictB));
        // another patient, grouped once as B
        long other = patient("BBNIDX" + suffix);
        long[] otherSample = sample(other, accession(), Timestamp.from(NOW.minus(Duration.ofDays(1))));
        result(otherSample[1], testAbo, finalized, "D", String.valueOf(dictB));

        PatientBloodProfile profile = profileService.getProfile(grouped.patientId());
        PatientBloodProfile otherProfile = profileService.getProfile(other);

        assertEquals(Abo.A, profile.group().abo());
        assertEquals(RhD.POS, profile.group().rhd());
        assertTrue(profile.group().confirmed());
        assertFalse(profile.group().discrepancy());
        assertEquals(4, profile.typings().size());
        assertEquals("Negative", profile.dat().value());
        assertEquals(List.of("bloodbank.test.antibodyScreen"), profile.notConfigured());
        CrossmatchResult crossmatch = profile.crossmatches().get(0);
        assertEquals(1, profile.crossmatches().size());
        assertEquals(Long.valueOf(grouped.crossmatchResultId()), crossmatch.resultId());
        assertTrue(crossmatch.compatible());
        assertEquals(grouped.collected().toInstant().plus(Duration.ofHours(72)), crossmatch.validUntil());

        assertEquals(Abo.B, otherProfile.group().abo());
        assertFalse(otherProfile.group().confirmed());
        assertEquals(1, otherProfile.typings().size());
        assertTrue(otherProfile.crossmatches().isEmpty());
    }

    @Test
    public void aCrossmatchOrderedThroughTheOrderFormIsValidFromItsCollection() {
        long patient = patient("BBNIDO" + suffix + ThreadLocalRandom.current().nextInt(1000));
        Timestamp collected = Timestamp.from(NOW.minus(Duration.ofHours(2)));
        long[] s = orderFormSample(patient, accession(), collected);
        result(s[1], testAbo, finalized, "D", String.valueOf(dictA));
        long crossmatch = result(s[1], testCrossmatch, finalized, "D", String.valueOf(dictCompatible));

        PatientBloodProfile profile = profileService.getProfile(patient);

        assertEquals(Long.valueOf(crossmatch), profile.crossmatches().get(0).resultId());
        assertEquals(collected.toInstant(), profile.crossmatches().get(0).collectedAt());
        assertEquals(collected.toInstant().plus(Duration.ofHours(72)), profile.crossmatches().get(0).validUntil());
        assertEquals(collected.toInstant(), profile.typings().get(0).collectedAt());
    }

    @Test
    public void theSecondCheckSettingIsSeededAsRequired() {
        assertEquals("REQUIRED", jdbcTemplate.queryForObject(
                "SELECT value FROM site_information WHERE name = 'bloodbank.secondCheck'", String.class));
        assertEquals(SecondCheckMode.REQUIRED, savedSettings.secondCheck());
        try {
            assertEquals(SecondCheckMode.COLLECTOR_CONFIRMS, settingsService
                    .update(Map.of("secondCheck", "COLLECTOR_CONFIRMS"), TEST_SYS_USER_ID).secondCheck());
        } finally {
            settingsService.update(Map.of("secondCheck", "REQUIRED"), TEST_SYS_USER_ID);
        }
    }

    @Test
    public void aLabNumberOrNationalIdResolvesToItsPatient() {
        Grouped grouped = groupedPatient();
        String nationalId = jdbcTemplate.queryForObject("SELECT national_id FROM patient WHERE id = ?", String.class,
                grouped.patientId());

        assertEquals(Long.valueOf(grouped.patientId()),
                profileService.resolvePatientIdentifier(grouped.lastAccession()));
        assertEquals(Long.valueOf(grouped.patientId()), profileService.resolvePatientIdentifier(nationalId));
        assertEquals(Long.valueOf(grouped.patientId()), profileService.resolvePatientIdentifier(nationalId + "-EXT"));
        // regression: an identifier matching nobody must answer null, not fail (and so
        // not poison the issue
        // transaction); the subject-number lookup it once tried no longer exists on
        // Patient
        assertNull(profileService.resolvePatientIdentifier("NO-SUCH-" + suffix));
    }

    // ------------------------------------------------------------------
    // constraints

    private ProductTypeView redCellsType() {
        return catalogService.getTypes().stream().filter(t -> "RED_CELLS".equals(t.code())).findFirst().orElseThrow();
    }

    /**
     * A product code prefix of this test's own, mapped to the seeded red cells
     * type.
     */
    private String mapOwnProductCode() {
        String prefix = "X" + ThreadLocalRandom.current().nextInt(1000, 9999);
        catalogService.createCode(new ProductCodeForm(prefix, redCellsType().id(), "test " + suffix, true),
                TEST_SYS_USER_ID);
        return prefix + "V00";
    }

    private String din() {
        // W + 4 facility characters + 26 + 6 digits, unique enough for one run
        return "W" + suffix.substring(0, 4).replaceAll("[^0-9A-Z]", "0") + "26"
                + ThreadLocalRandom.current().nextInt(100_000, 999_999);
    }

    private BloodUnit unitRow(String din, String productCode) {
        BloodUnit unit = new BloodUnit();
        unit.setDonationNumber(din);
        unit.setProductCode(productCode);
        unit.setProductType(typeDAO.getByCode("RED_CELLS").orElseThrow());
        unit.setAbo(Abo.O);
        unit.setRhd(RhD.NEG);
        unit.setExpiresAt(Timestamp.from(NOW.plus(Duration.ofDays(20))));
        unit.setStatus(UnitStatus.AVAILABLE);
        unit.setReceivedAt(Timestamp.from(NOW));
        unit.setReceivedBy(1);
        unit.setSysUserId(TEST_SYS_USER_ID);
        return unit;
    }

    @Test
    public void theDatabaseRefusesTheSameDonationAndProductTwice() {
        String din = din();
        unitDAO.insert(unitRow(din, "E0291V00"));
        // the same donation as another component is a different unit
        unitDAO.insert(unitRow(din, "E2555V00"));

        assertThrows(RuntimeException.class, () -> unitDAO.insert(unitRow(din, "E0291V00")));
        assertEquals(2, unitDAO.getByDonationNumber(din).size());
    }

    @Test
    public void theDatabaseAllowsOneActiveReservationPerUnit() {
        Grouped grouped = groupedPatient();
        BloodUnit unit = unitRow(din(), "E0291V00");
        unitDAO.insert(unit);
        reservationDAO.insert(reservation(unit, grouped.patientId(), ReservationStatus.ACTIVE));
        // a released one beside it is fine
        reservationDAO.insert(reservation(unit, grouped.patientId(), ReservationStatus.RELEASED));

        assertThrows(RuntimeException.class,
                () -> reservationDAO.insert(reservation(unit, grouped.patientId(), ReservationStatus.ACTIVE)));
    }

    private static BloodUnitReservation reservation(BloodUnit unit, long patientId, ReservationStatus status) {
        BloodUnitReservation reservation = new BloodUnitReservation();
        reservation.setUnit(unit);
        reservation.setPatientId(patientId);
        reservation.setStatus(status);
        reservation.setReservedBy(1);
        reservation.setReservedAt(Timestamp.from(NOW));
        reservation.setSysUserId(TEST_SYS_USER_ID);
        return reservation;
    }

    @Test
    public void theTrailCannotBeEditedOrDeleted() {
        String productCode = mapOwnProductCode();
        String din = din();
        ReceiveLineResult received = unitService
                .receive(List.of(new ReceiveLine(null, din, productCode, "O NEG", "2026-11-30", null, null, null)),
                        TEST_SYS_USER_ID)
                .get(0);
        assertTrue(String.valueOf(received.errors()), received.received());

        Long eventId = jdbcTemplate.queryForObject("SELECT id FROM blood_unit_event WHERE unit_id = ?", Long.class,
                received.unitId());
        DataAccessException update = assertThrows(DataAccessException.class,
                () -> jdbcTemplate.update("UPDATE blood_unit_event SET reason = 'changed' WHERE id = ?", eventId));
        assertTrue(update.getMessage(), update.getMessage().contains("append-only"));
        assertThrows(DataAccessException.class,
                () -> jdbcTemplate.update("DELETE FROM blood_unit_event WHERE id = ?", eventId));
        assertEquals(1, traceService.forUnit(received.unitId()).size());
    }

    @Test
    public void aSplitOfAStockedDonationMustCarryItsGroup() {
        String productCode = mapOwnProductCode();
        String split = productCode.substring(0, 5) + "V01";
        String din = din();
        assertTrue(unitService
                .receive(List.of(new ReceiveLine(null, din, productCode, "O NEG", "2026-11-30", null, null, null)),
                        TEST_SYS_USER_ID)
                .get(0).received());

        ReceiveLineResult otherGroup = unitService
                .receive(List.of(new ReceiveLine(null, din, split, "A POS", "2026-11-30", null, null, null)),
                        TEST_SYS_USER_ID)
                .get(0);
        ReceiveLineResult sameGroup = unitService
                .receive(List.of(new ReceiveLine(null, din, split, "O NEG", "2026-11-30", null, null, null)),
                        TEST_SYS_USER_ID)
                .get(0);

        assertFalse(otherGroup.received());
        assertEquals(List.of("bloodbank.receive.error.groupConflict"),
                otherGroup.errors().stream().map(e -> e.code()).toList());
        assertEquals(List.of(din, "O", "NEG"), otherGroup.errors().get(0).args());
        assertTrue(String.valueOf(sameGroup.errors()), sameGroup.received());
        assertEquals(2, unitDAO.getByDonationNumber(din).size());
    }

    // ------------------------------------------------------------------ the whole
    // way

    @Test
    public void aUnitGoesFromReceiptToIssueAndBackWithItsWholeTrail() {
        Grouped grouped = groupedPatient();
        String productCode = mapOwnProductCode();
        String din = din();

        ReceiveLineResult received = unitService
                .receive(List.of(new ReceiveLine(List.of("=" + din + "00", "=%9500", "=<" + productCode, "=>026334"),
                        null, null, null, null, null, "Example Blood Service", 280)), TEST_SYS_USER_ID)
                .get(0);
        assertTrue(String.valueOf(received.errors()), received.received());
        Long unitId = received.unitId();

        // a second delivery of the same unit is refused, line by line
        ReceiveLineResult again = unitService
                .receive(List.of(new ReceiveLine(null, din, productCode, "O NEG", "2026-11-30", null, null, null)),
                        TEST_SYS_USER_ID)
                .get(0);
        assertFalse(again.received());

        ReservationView reservation = issueService.reserve(unitId, grouped.patientId(), grouped.crossmatchResultId(),
                TEST_SYS_USER_ID);
        assertEquals(ReservationStatus.ACTIVE, reservation.status());
        // the crossmatch now shows which unit it covers
        assertEquals(unitId, profileService.getProfile(grouped.patientId()).crossmatches().get(0).linkedUnitId());

        IssueCheck otherBag = issueService.check(unitId, grouped.lastAccession(), "G123498654322");
        assertFalse(String.valueOf(otherBag.checks()), otherBag.allowed());
        IssueCheck dryRun = issueService.check(unitId, grouped.lastAccession(), din);
        assertTrue(String.valueOf(dryRun.checks()), dryRun.allowed());

        BloodIssueServiceImpl issueTarget = target(issueService, BloodIssueServiceImpl.class);
        Object realVerifier = ReflectionTestUtils.getField(issueTarget, "secondPersonVerifier");
        SecondPersonVerifier verifiedAsUser2 = (issuer, login, password,
                otp) -> "second".equals(login) && "pw".equals(password)
                        ? new Verification(SecondCheckOutcome.VERIFIED, 2)
                        : new Verification(SecondCheckOutcome.INVALID_CREDENTIALS, null);
        ReflectionTestUtils.setField(issueTarget, "secondPersonVerifier", verifiedAsUser2);
        try {
            UnitActionResult issued = issueService.issue(unitId, new IssueRequest(grouped.lastAccession(), "Ward 3",
                    "Porter Example", "second", "pw", null, null, din), TEST_SYS_USER_ID);
            assertEquals(UnitStatus.ISSUED, issued.unit().status());
        } finally {
            ReflectionTestUtils.setField(issueTarget, "secondPersonVerifier", realVerifier);
        }

        // back within the window (the clock has not moved): to stock
        UnitActionResult returned = issueService.returnUnit(unitId, null, TEST_SYS_USER_ID);
        assertEquals(UnitStatus.AVAILABLE, returned.unit().status());

        List<EventView> trail = traceService.forDonationNumber(din);
        assertEquals(List.of(EventType.RECEIVED, EventType.RESERVED, EventType.ISSUED, EventType.RETURNED),
                trail.stream().map(EventView::eventType).toList());
        EventView issue = trail.get(2);
        assertEquals(Integer.valueOf(2), issue.secondCheckerId());
        assertEquals("Ward 3", issue.location());
        assertEquals(Long.valueOf(grouped.patientId()), issue.patientId());
        // the trail names the patient and the crossmatch sample, not just record
        // numbers
        assertEquals("Bloodbank Fictional" + suffix, issue.patientName());
        assertEquals(grouped.lastAccession(), issue.labNumber());
        assertTrue(issue.details(), issue.details().endsWith("; secondCheck: REQUIRED"));
        // receipt names no patient; the other three do
        assertEquals(List.of(EventType.RESERVED, EventType.ISSUED, EventType.RETURNED),
                traceService.forPatient(grouped.patientId()).stream().map(EventView::eventType).toList());
    }
}
