package org.openelisglobal.analyzer.service;

import org.openelisglobal.common.log.LogEvent;
import org.openelisglobal.sample.action.util.SamplePatientUpdateData;
import org.openelisglobal.sample.event.SamplePatientUpdateDataCreatedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Queues a new order for the analysers that take orders. Every order entry path
 * (Add Order, incoming FHIR orders) saves through
 * {@code SamplePatientEntryService.persistData}, which publishes this event. It
 * runs only after the order has committed, so nothing here can fail or roll
 * back the order: a failure is logged and the order can be sent by hand from
 * the Order worklist.
 */
@Component
public class AnalyzerOrderEnqueueListener {

    private final AnalyzerOrderDispatchService dispatchService;

    public AnalyzerOrderEnqueueListener(AnalyzerOrderDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderSaved(SamplePatientUpdateDataCreatedEvent event) {
        SamplePatientUpdateData updateData = event.getUpdateData();
        if (updateData == null || updateData.getSample() == null || updateData.getSample().getId() == null) {
            return;
        }
        try {
            dispatchService.enqueueSample(updateData.getSample().getId());
        } catch (RuntimeException e) {
            LogEvent.logError(getClass().getSimpleName(), "onOrderSaved",
                    "could not queue order " + updateData.getAccessionNumber() + " for analysers: " + e.getMessage());
        }
    }
}
