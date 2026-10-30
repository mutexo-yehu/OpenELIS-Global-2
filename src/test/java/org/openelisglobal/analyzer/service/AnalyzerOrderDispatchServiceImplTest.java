package org.openelisglobal.analyzer.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.openelisglobal.analysis.service.AnalysisService;
import org.openelisglobal.analysis.valueholder.Analysis;
import org.openelisglobal.analyzer.dao.AnalyzerOrderDispatchDAO;
import org.openelisglobal.analyzer.service.AnalyzerOrderDispatchService.EnqueueOutcome;
import org.openelisglobal.analyzer.service.AnalyzerOrderDispatchService.EnqueueResult;
import org.openelisglobal.analyzer.valueholder.Analyzer;
import org.openelisglobal.analyzer.valueholder.AnalyzerOrderDispatch;
import org.openelisglobal.analyzer.valueholder.AnalyzerOrderDispatch.Status;
import org.openelisglobal.patient.valueholder.Patient;
import org.openelisglobal.sample.service.SampleService;
import org.openelisglobal.sample.valueholder.Sample;
import org.openelisglobal.samplehuman.service.SampleHumanService;

@RunWith(MockitoJUnitRunner.class)
public class AnalyzerOrderDispatchServiceImplTest {

    private static final String SAMPLE_ID = "501";
    private static final String LAB_NO = "DEV01260000000000130";
    private static final String COVID_LOINC = "94500-6";

    @Mock
    private AnalyzerOrderDispatchDAO dispatchDAO;
    @Mock
    private AnalysisService analysisService;
    @Mock
    private SampleService sampleService;
    @Mock
    private SampleHumanService sampleHumanService;
    @Mock
    private AnalyzerService analyzerService;
    @Mock
    private AnalyzerOrderCapability orderCapability;
    @Mock
    private AnalyzerWorklistSettings settings;
    @Mock
    private BridgeOrderClient orderClient;

    private AnalyzerOrderDispatchServiceImpl service;
    private Sample sample;
    private Analyzer genexpert;
    private Analyzer mindray;

    @Before
    public void setUp() {
        service = new AnalyzerOrderDispatchServiceImpl(dispatchDAO, analysisService, sampleService, sampleHumanService,
                analyzerService, orderCapability, settings, orderClient);
        sample = new Sample();
        sample.setId(SAMPLE_ID);
        sample.setAccessionNumber(LAB_NO);
        genexpert = analyzer("5", "GeneXpert (mock) - Molecular", "conn-gx");
        mindray = analyzer("3", "Mindray BC-5380 (simulated) - Haematology", "conn-mr");
    }

    @Test
    public void enqueueSample_testsMappedToTwoOrderTakingAnalysers_queuesOneRowPerAnalyser() {
        Analyzer second = analyzer("7", "GeneXpert bench 2", "conn-gx2");
        givenOrder(test("301", "COVID-19 PCR", COVID_LOINC), test("302", "MTB/RIF", "88174-8"));
        when(analyzerService.getCapabilitiesForTest("301")).thenReturn(List.of(capability(genexpert, "SARSCOV2")));
        when(analyzerService.getCapabilitiesForTest("302"))
                .thenReturn(List.of(capability(genexpert, "MTB"), capability(second, "MTB")));
        takesOrders(genexpert, true);
        takesOrders(second, true);
        when(dispatchDAO.findBySampleAndAnalyzer(SAMPLE_ID, "5")).thenReturn(Optional.empty());
        when(dispatchDAO.findBySampleAndAnalyzer(SAMPLE_ID, "7")).thenReturn(Optional.empty());

        EnqueueResult result = service.enqueueSample(SAMPLE_ID);

        assertEquals(new EnqueueResult(EnqueueOutcome.QUEUED, 2), result);
        ArgumentCaptor<AnalyzerOrderDispatch> rows = ArgumentCaptor.forClass(AnalyzerOrderDispatch.class);
        verify(dispatchDAO, org.mockito.Mockito.times(2)).insert(rows.capture());
        AnalyzerOrderDispatch first = rows.getAllValues().get(0);
        assertEquals("5", first.getAnalyzerId());
        assertEquals(LAB_NO, first.getAccessionNumber());
        assertEquals("301,302", first.getTestIds());
        assertEquals(List.of(COVID_LOINC, "88174-8"), first.getLoincCodeList());
        assertEquals("COVID-19 PCR, MTB/RIF", first.getTestNames());
        assertEquals(Status.PENDING, first.getStatus());
        assertNotNull(first.getCreatedAt());
        AnalyzerOrderDispatch other = rows.getAllValues().get(1);
        assertEquals("7", other.getAnalyzerId());
        assertEquals(List.of("88174-8"), other.getLoincCodeList());
    }

    @Test
    public void enqueueSample_noTestMappedToAnAnalyser_queuesNothing() {
        givenOrder(test("380", "WBC", "6690-2"));
        when(analyzerService.getCapabilitiesForTest("380")).thenReturn(List.of());

        EnqueueResult result = service.enqueueSample(SAMPLE_ID);

        assertEquals(EnqueueOutcome.NOTHING_TO_SEND, result.outcome());
        verify(dispatchDAO, never()).insert(org.mockito.ArgumentMatchers.isA(AnalyzerOrderDispatch.class));
    }

    @Test
    public void enqueueSample_resultsOnlyAnalyser_isNeverQueued() {
        givenOrder(test("380", "WBC", "6690-2"));
        when(analyzerService.getCapabilitiesForTest("380")).thenReturn(List.of(capability(mindray, "WBC")));
        takesOrders(mindray, false);

        EnqueueResult result = service.enqueueSample(SAMPLE_ID);

        assertEquals(EnqueueOutcome.NOTHING_TO_SEND, result.outcome());
        verify(dispatchDAO, never()).insert(org.mockito.ArgumentMatchers.isA(AnalyzerOrderDispatch.class));
    }

    @Test
    public void enqueueSample_rowAlreadyThere_isLeftAlone() {
        givenOrder(test("301", "COVID-19 PCR", COVID_LOINC));
        when(analyzerService.getCapabilitiesForTest("301")).thenReturn(List.of(capability(genexpert, "SARSCOV2")));
        takesOrders(genexpert, true);
        AnalyzerOrderDispatch sent = row(Status.SENT, 1);
        when(dispatchDAO.findBySampleAndAnalyzer(SAMPLE_ID, "5")).thenReturn(Optional.of(sent));

        assertEquals(EnqueueOutcome.NOTHING_TO_SEND, service.enqueueSample(SAMPLE_ID).outcome());
        verify(dispatchDAO, never()).update(sent);
    }

    @Test
    public void enqueueSample_settingOff_queuesNothing() {
        when(settings.isEnabled()).thenReturn(false);

        assertEquals(EnqueueOutcome.DISABLED, service.enqueueSample(SAMPLE_ID).outcome());
        verifyZeroInteractions(sampleService, analysisService, dispatchDAO);
    }

    @Test
    public void enqueueAccession_existingRow_isSentAgain() {
        when(settings.isEnabled()).thenReturn(true);
        when(sampleService.getSampleByAccessionNumber(LAB_NO)).thenReturn(sample);
        when(analysisService.getAnalysesBySampleId(SAMPLE_ID))
                .thenReturn(List.of(analysis(test("301", "COVID-19 PCR", COVID_LOINC))));
        when(analyzerService.getCapabilitiesForTest("301")).thenReturn(List.of(capability(genexpert, "SARSCOV2")));
        takesOrders(genexpert, true);
        AnalyzerOrderDispatch failed = row(Status.FAILED, 5);
        when(dispatchDAO.findBySampleAndAnalyzer(SAMPLE_ID, "5")).thenReturn(Optional.of(failed));

        EnqueueResult result = service.enqueueAccession(" " + LAB_NO + " ", "17");

        assertEquals(new EnqueueResult(EnqueueOutcome.QUEUED, 1), result);
        verify(dispatchDAO).update(failed);
        assertEquals(Status.PENDING, failed.getStatus());
        assertEquals(0, failed.getAttempts());
        assertEquals("17", failed.getSysUserId());
    }

    @Test
    public void enqueueAccession_unknownLabNumber_isNotFound() {
        when(settings.isEnabled()).thenReturn(true);
        when(sampleService.getSampleByAccessionNumber("NOPE")).thenReturn(null);

        assertEquals(EnqueueOutcome.NOT_FOUND, service.enqueueAccession("NOPE", "17").outcome());
    }

    @Test
    public void send_bridgeAccepts_marksTheRowSent() {
        AnalyzerOrderDispatch pending = row(Status.PENDING, 0);
        givenSendable(pending);
        when(orderClient.send("conn-gx", LAB_NO, "DEMO-AB-0001", List.of(COVID_LOINC)))
                .thenReturn(new BridgeOrderClient.SendResult(true, null));

        service.send("9");

        assertEquals(Status.SENT, pending.getStatus());
        assertEquals(1, pending.getAttempts());
        assertNotNull(pending.getSentAt());
        assertNull(pending.getLastError());
        verify(dispatchDAO).update(pending);
    }

    @Test
    public void send_bridgeRefuses_marksTheRowFailedWithTheError() {
        AnalyzerOrderDispatch pending = row(Status.PENDING, 0);
        givenSendable(pending);
        when(orderClient.send("conn-gx", LAB_NO, "DEMO-AB-0001", List.of(COVID_LOINC)))
                .thenReturn(new BridgeOrderClient.SendResult(false, "HTTP 502: ASTM send failed"));

        service.send("9");

        assertEquals(Status.FAILED, pending.getStatus());
        assertEquals(1, pending.getAttempts());
        assertEquals("HTTP 502: ASTM send failed", pending.getLastError());
        assertNull(pending.getSentAt());
    }

    @Test
    public void retry_failedRow_goesBackToPending() {
        AnalyzerOrderDispatch failed = row(Status.FAILED, 5);
        failed.setLastError("HTTP 502: ASTM send failed");
        when(dispatchDAO.get("9")).thenReturn(Optional.of(failed));

        assertTrue(service.retry("9", "17"));
        assertEquals(Status.PENDING, failed.getStatus());
        assertEquals(0, failed.getAttempts());
        verify(dispatchDAO).update(failed);
    }

    @Test
    public void retry_sentRow_isRefused() {
        AnalyzerOrderDispatch sent = row(Status.SENT, 1);
        when(dispatchDAO.get("9")).thenReturn(Optional.of(sent));

        assertFalse(service.retry("9", "17"));
        verify(dispatchDAO, never()).update(sent);
    }

    @Test
    public void isDue_followsTheBackoffAndStopsAfterFiveAttempts() {
        Instant now = Instant.parse("2026-10-30T12:00:00Z");
        assertTrue(AnalyzerOrderDispatchServiceImpl.isDue(row(Status.PENDING, 0), now));
        assertFalse(AnalyzerOrderDispatchServiceImpl.isDue(failedAt(1, now.minusSeconds(10)), now));
        assertTrue(AnalyzerOrderDispatchServiceImpl.isDue(failedAt(1, now.minusSeconds(15)), now));
        assertFalse(AnalyzerOrderDispatchServiceImpl.isDue(failedAt(2, now.minusSeconds(29)), now));
        assertTrue(AnalyzerOrderDispatchServiceImpl.isDue(failedAt(3, now.minusSeconds(60)), now));
        assertFalse(AnalyzerOrderDispatchServiceImpl.isDue(failedAt(4, now.minusSeconds(119)), now));
        assertTrue(AnalyzerOrderDispatchServiceImpl.isDue(failedAt(4, now.minusSeconds(120)), now));
        assertFalse(AnalyzerOrderDispatchServiceImpl.isDue(failedAt(5, now.minusSeconds(3600)), now));
        assertFalse(AnalyzerOrderDispatchServiceImpl.isDue(row(Status.SENT, 1), now));
    }

    private void givenOrder(org.openelisglobal.test.valueholder.Test... tests) {
        when(settings.isEnabled()).thenReturn(true);
        when(sampleService.get(SAMPLE_ID)).thenReturn(sample);
        when(analysisService.getAnalysesBySampleId(SAMPLE_ID))
                .thenReturn(java.util.Arrays.stream(tests).map(AnalyzerOrderDispatchServiceImplTest::analysis).toList());
    }

    private void givenSendable(AnalyzerOrderDispatch row) {
        when(dispatchDAO.get("9")).thenReturn(Optional.of(row));
        when(analyzerService.getWithMapping("5")).thenReturn(Optional.of(genexpert));
        when(sampleService.get(SAMPLE_ID)).thenReturn(sample);
        Patient patient = new Patient();
        patient.setId("88");
        patient.setNationalId("DEMO-AB-0001");
        when(sampleHumanService.getPatientForSample(sample)).thenReturn(patient);
    }

    private void takesOrders(Analyzer analyzer, boolean takes) {
        when(analyzerService.getWithMapping(analyzer.getId())).thenReturn(Optional.of(analyzer));
        when(orderCapability.acceptsOrders(analyzer)).thenReturn(takes);
    }

    private static Analyzer analyzer(String id, String name, String connectionId) {
        Analyzer analyzer = new Analyzer();
        analyzer.setId(id);
        analyzer.setName(name);
        analyzer.setBridgeConnectionId(connectionId);
        analyzer.setStatus(Analyzer.AnalyzerStatus.ACTIVE);
        return analyzer;
    }

    private static AnalyzerTestCapability capability(Analyzer analyzer, String code) {
        return new AnalyzerTestCapability(analyzer.getId(), analyzer.getName(), code);
    }

    private static org.openelisglobal.test.valueholder.Test test(String id, String name, String loinc) {
        org.openelisglobal.test.valueholder.Test test = new org.openelisglobal.test.valueholder.Test();
        test.setId(id);
        test.setDescription(name);
        test.setLoinc(loinc);
        return test;
    }

    private static Analysis analysis(org.openelisglobal.test.valueholder.Test test) {
        Analysis analysis = new Analysis();
        analysis.setTest(test);
        return analysis;
    }

    private static AnalyzerOrderDispatch row(Status status, int attempts) {
        AnalyzerOrderDispatch row = new AnalyzerOrderDispatch();
        row.setId("9");
        row.setSampleId(SAMPLE_ID);
        row.setAccessionNumber(LAB_NO);
        row.setAnalyzerId("5");
        row.setTestIds("301");
        row.setLoincCodes(COVID_LOINC);
        row.setStatus(status);
        row.setAttempts(attempts);
        return row;
    }

    private static AnalyzerOrderDispatch failedAt(int attempts, Instant when) {
        AnalyzerOrderDispatch row = row(Status.FAILED, attempts);
        row.setLastupdated(Timestamp.from(when));
        return row;
    }
}
