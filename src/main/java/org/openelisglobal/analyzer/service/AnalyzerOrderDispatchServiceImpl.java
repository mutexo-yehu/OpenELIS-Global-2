package org.openelisglobal.analyzer.service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.openelisglobal.analysis.service.AnalysisService;
import org.openelisglobal.analysis.valueholder.Analysis;
import org.openelisglobal.analyzer.dao.AnalyzerOrderDispatchDAO;
import org.openelisglobal.analyzer.valueholder.Analyzer;
import org.openelisglobal.analyzer.valueholder.AnalyzerOrderDispatch;
import org.openelisglobal.analyzer.valueholder.AnalyzerOrderDispatch.Status;
import org.openelisglobal.patient.valueholder.Patient;
import org.openelisglobal.sample.service.SampleService;
import org.openelisglobal.sample.valueholder.Sample;
import org.openelisglobal.samplehuman.service.SampleHumanService;
import org.openelisglobal.test.valueholder.Test;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalyzerOrderDispatchServiceImpl implements AnalyzerOrderDispatchService {

    static final String SYSTEM_USER_ID = "1";
    /** Wait after the 1st, 2nd, 3rd and 4th failed attempt. */
    static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(15), Duration.ofSeconds(30),
            Duration.ofSeconds(60), Duration.ofMinutes(2));

    private final AnalyzerOrderDispatchDAO dispatchDAO;
    private final AnalysisService analysisService;
    private final SampleService sampleService;
    private final SampleHumanService sampleHumanService;
    private final AnalyzerService analyzerService;
    private final AnalyzerOrderCapability orderCapability;
    private final AnalyzerWorklistSettings settings;
    private final BridgeOrderClient orderClient;

    public AnalyzerOrderDispatchServiceImpl(AnalyzerOrderDispatchDAO dispatchDAO, AnalysisService analysisService,
            SampleService sampleService, SampleHumanService sampleHumanService, AnalyzerService analyzerService,
            AnalyzerOrderCapability orderCapability, AnalyzerWorklistSettings settings, BridgeOrderClient orderClient) {
        this.dispatchDAO = dispatchDAO;
        this.analysisService = analysisService;
        this.sampleService = sampleService;
        this.sampleHumanService = sampleHumanService;
        this.analyzerService = analyzerService;
        this.orderCapability = orderCapability;
        this.settings = settings;
        this.orderClient = orderClient;
    }

    /** The tests of one sample bound for one analyser. */
    private static final class Target {
        private final Set<String> testIds = new LinkedHashSet<>();
        private final Set<String> testNames = new LinkedHashSet<>();
        private final Set<String> loincCodes = new LinkedHashSet<>();
    }

    @Override
    @Transactional
    public EnqueueResult enqueueSample(String sampleId) {
        if (!settings.isEnabled()) {
            return new EnqueueResult(EnqueueOutcome.DISABLED, 0);
        }
        Sample sample = sampleId == null ? null : sampleService.get(sampleId);
        if (sample == null) {
            return new EnqueueResult(EnqueueOutcome.NOT_FOUND, 0);
        }
        return enqueue(sample, false, SYSTEM_USER_ID);
    }

    @Override
    @Transactional
    public EnqueueResult enqueueAccession(String accessionNumber, String sysUserId) {
        if (!settings.isEnabled()) {
            return new EnqueueResult(EnqueueOutcome.DISABLED, 0);
        }
        Sample sample = accessionNumber == null || accessionNumber.isBlank() ? null
                : sampleService.getSampleByAccessionNumber(accessionNumber.trim());
        if (sample == null) {
            return new EnqueueResult(EnqueueOutcome.NOT_FOUND, 0);
        }
        return enqueue(sample, true, sysUserId);
    }

    private EnqueueResult enqueue(Sample sample, boolean resend, String sysUserId) {
        Map<String, Target> targets = new LinkedHashMap<>();
        Map<String, Boolean> accepts = new LinkedHashMap<>();
        for (Analysis analysis : analysisService.getAnalysesBySampleId(sample.getId())) {
            Test test = analysis.getTest();
            if (test == null || test.getLoinc() == null || test.getLoinc().isBlank()) {
                continue; // the Bridge orders by LOINC
            }
            for (AnalyzerTestCapability capability : analyzerService.getCapabilitiesForTest(test.getId())) {
                String analyzerId = capability.analyzerId();
                boolean takesOrders = accepts.computeIfAbsent(analyzerId,
                        id -> orderCapability.acceptsOrders(analyzerService.getWithMapping(id).orElse(null)));
                if (!takesOrders) {
                    continue;
                }
                Target target = targets.computeIfAbsent(analyzerId, id -> new Target());
                target.testIds.add(test.getId());
                target.testNames.add(testName(test));
                target.loincCodes.add(test.getLoinc().trim());
            }
        }
        int rows = 0;
        for (Map.Entry<String, Target> entry : targets.entrySet()) {
            Optional<AnalyzerOrderDispatch> existing = dispatchDAO.findBySampleAndAnalyzer(sample.getId(),
                    entry.getKey());
            AnalyzerOrderDispatch row = existing.orElseGet(AnalyzerOrderDispatch::new);
            if (existing.isPresent() && !resend) {
                continue;
            }
            Target target = entry.getValue();
            row.setSampleId(sample.getId());
            row.setAccessionNumber(sample.getAccessionNumber());
            row.setAnalyzerId(entry.getKey());
            row.setTestIds(String.join(",", target.testIds));
            row.setTestNames(String.join(", ", target.testNames));
            row.setLoincCodes(String.join(",", target.loincCodes));
            row.setStatus(Status.PENDING);
            row.setAttempts(0);
            row.setLastError(null);
            row.setSysUserId(sysUserId);
            if (existing.isPresent()) {
                dispatchDAO.update(row);
            } else {
                row.setCreatedAt(Timestamp.from(Instant.now()));
                dispatchDAO.insert(row);
            }
            rows++;
        }
        return new EnqueueResult(rows > 0 ? EnqueueOutcome.QUEUED : EnqueueOutcome.NOTHING_TO_SEND, rows);
    }

    private static String testName(Test test) {
        if (test.getLocalizedTestName() != null && test.getLocalizedTestName().getLocalizedValue() != null) {
            return test.getLocalizedTestName().getLocalizedValue();
        }
        return test.getDescription() == null ? test.getId() : test.getDescription();
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> dueIds(Instant now, int limit) {
        List<String> due = new ArrayList<>();
        for (AnalyzerOrderDispatch row : dispatchDAO.findSendable(MAX_ATTEMPTS, limit)) {
            if (isDue(row, now)) {
                due.add(row.getId());
            }
        }
        return due;
    }

    static boolean isDue(AnalyzerOrderDispatch row, Instant now) {
        if (row.getStatus() == Status.PENDING) {
            return true;
        }
        if (row.getStatus() != Status.FAILED || row.getAttempts() >= MAX_ATTEMPTS) {
            return false;
        }
        Duration wait = BACKOFF.get(Math.max(0, Math.min(row.getAttempts(), BACKOFF.size()) - 1));
        Timestamp last = row.getLastupdated();
        return last == null || !now.isBefore(last.toInstant().plus(wait));
    }

    @Override
    @Transactional
    public void send(String dispatchId) {
        AnalyzerOrderDispatch row = dispatchDAO.get(dispatchId).orElse(null);
        if (row == null || row.getStatus() == Status.SENT) {
            return;
        }
        Analyzer analyzer = analyzerService.getWithMapping(row.getAnalyzerId()).orElse(null);
        BridgeOrderClient.SendResult result;
        if (analyzer == null || analyzer.getBridgeConnectionId() == null) {
            result = new BridgeOrderClient.SendResult(false, "The analyser has no Bridge connection");
        } else {
            result = orderClient.send(analyzer.getBridgeConnectionId(), row.getAccessionNumber(),
                    patientId(row.getSampleId()), row.getLoincCodeList());
        }
        row.setAttempts(row.getAttempts() + 1);
        row.setSysUserId(SYSTEM_USER_ID);
        if (result.dispatched()) {
            row.setStatus(Status.SENT);
            row.setSentAt(Timestamp.from(Instant.now()));
            row.setLastError(null);
        } else {
            row.setStatus(Status.FAILED);
            row.setLastError(result.error());
        }
        dispatchDAO.update(row);
    }

    /** As the patient is known on the order: national ID if there is one. */
    private String patientId(String sampleId) {
        Sample sample = sampleService.get(sampleId);
        Patient patient = sample == null ? null : sampleHumanService.getPatientForSample(sample);
        if (patient == null) {
            return "";
        }
        return patient.getNationalId() != null && !patient.getNationalId().isBlank() ? patient.getNationalId()
                : patient.getId();
    }

    @Override
    @Transactional
    public boolean retry(String dispatchId, String sysUserId) {
        AnalyzerOrderDispatch row = dispatchId == null ? null : dispatchDAO.get(dispatchId).orElse(null);
        if (row == null || row.getStatus() != Status.FAILED) {
            return false;
        }
        row.setStatus(Status.PENDING);
        row.setAttempts(0);
        row.setSysUserId(sysUserId);
        dispatchDAO.update(row);
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AnalyzerOrderDispatchView> recent(int limit) {
        Map<String, String> names = new LinkedHashMap<>();
        List<AnalyzerOrderDispatchView> views = new ArrayList<>();
        for (AnalyzerOrderDispatch row : dispatchDAO.findRecent(limit)) {
            String name = names.computeIfAbsent(row.getAnalyzerId(),
                    id -> analyzerService.getWithMapping(id).map(Analyzer::getName).orElse(id));
            views.add(new AnalyzerOrderDispatchView(row.getId(), row.getAccessionNumber(), row.getAnalyzerId(), name,
                    row.getTestNames(), row.getLoincCodeList(), row.getStatus().name(), row.getAttempts(),
                    row.getLastError(), iso(row.getCreatedAt()), iso(row.getSentAt()), iso(row.getLastupdated())));
        }
        return views;
    }

    private static String iso(Timestamp time) {
        return time == null ? null : time.toInstant().toString();
    }
}
