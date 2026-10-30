package org.openelisglobal.analyzer.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.Test;
import org.openelisglobal.analyzer.valueholder.Analyzer;
import org.openelisglobal.analyzer.valueholder.AnalyzerMapping;
import org.openelisglobal.sample.action.util.SamplePatientUpdateData;
import org.openelisglobal.sample.event.SamplePatientUpdateDataCreatedEvent;
import org.openelisglobal.sample.valueholder.Sample;
import org.openelisglobal.siteinformation.service.SiteInformationService;
import org.openelisglobal.siteinformation.valueholder.SiteInformation;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The pieces around the worklist queue: the order-save hook, the profile test,
 * the Bridge call, the setting and the sender.
 */
public class AnalyzerOrderWorklistPartsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ---- the order-save hook

    @Test
    public void listener_runsOnlyAfterTheOrderHasCommitted() throws NoSuchMethodException {
        TransactionalEventListener annotation = AnalyzerOrderEnqueueListener.class
                .getMethod("onOrderSaved", SamplePatientUpdateDataCreatedEvent.class)
                .getAnnotation(TransactionalEventListener.class);
        assertEquals(TransactionPhase.AFTER_COMMIT, annotation.phase());
    }

    @Test
    public void listener_enqueueFailure_isLoggedAndNeverReachesTheOrderSave() {
        AnalyzerOrderDispatchService dispatchService = mock(AnalyzerOrderDispatchService.class);
        when(dispatchService.enqueueSample("501")).thenThrow(new IllegalStateException("database is down"));
        SamplePatientUpdateData updateData = mock(SamplePatientUpdateData.class);
        Sample sample = new Sample();
        sample.setId("501");
        when(updateData.getSample()).thenReturn(sample);
        when(updateData.getAccessionNumber()).thenReturn("DEV01260000000000130");

        new AnalyzerOrderEnqueueListener(dispatchService)
                .onOrderSaved(new SamplePatientUpdateDataCreatedEvent(this, updateData, null, null));

        verify(dispatchService).enqueueSample("501");
    }

    // ---- which analysers take orders

    @Test
    public void capability_genexpertProfileTakesOrders_mindrayProfileDoesNot() throws IOException {
        BridgeProfileCatalogService catalog = mock(BridgeProfileCatalogService.class);
        when(catalog.getProfile("cepheid-genexpert-astm", 1)).thenReturn(profile(true, true));
        when(catalog.getProfile("mindray-bc5380", 2)).thenReturn(profile(false, false));
        AnalyzerOrderCapability capability = new AnalyzerOrderCapability(catalog);

        assertTrue(capability.acceptsOrders(analyzer("cepheid-genexpert-astm", 1, Analyzer.AnalyzerStatus.ACTIVE)));
        assertTrue(capability.acceptsOrders(analyzer("cepheid-genexpert-astm", 1, Analyzer.AnalyzerStatus.ACTIVE)));
        assertFalse(capability.acceptsOrders(analyzer("mindray-bc5380", 2, Analyzer.AnalyzerStatus.ACTIVE)));
        // a profile revision is read from the Bridge once
        verify(catalog, times(1)).getProfile("cepheid-genexpert-astm", 1);
    }

    @Test
    public void capability_inactiveAnalyser_takesNoOrders() {
        BridgeProfileCatalogService catalog = mock(BridgeProfileCatalogService.class);
        AnalyzerOrderCapability capability = new AnalyzerOrderCapability(catalog);

        assertFalse(capability.acceptsOrders(analyzer("cepheid-genexpert-astm", 1, Analyzer.AnalyzerStatus.INACTIVE)));
        verify(catalog, never()).getProfile("cepheid-genexpert-astm", 1);
    }

    @Test
    public void capability_bridgeUnreachable_takesNoOrdersAndAsksAgainNextTime() throws IOException {
        BridgeProfileCatalogService catalog = mock(BridgeProfileCatalogService.class);
        when(catalog.getProfile("cepheid-genexpert-astm", 1))
                .thenThrow(new BridgeProfileCatalogException("Bridge profile catalog request failed"))
                .thenReturn(profile(true, true));
        AnalyzerOrderCapability capability = new AnalyzerOrderCapability(catalog);
        Analyzer genexpert = analyzer("cepheid-genexpert-astm", 1, Analyzer.AnalyzerStatus.ACTIVE);

        assertFalse(capability.acceptsOrders(genexpert));
        assertTrue(capability.acceptsOrders(genexpert));
    }

    // ---- the Bridge call

    @Test
    public void client_postsTheOrderAsTheBridgeExpects() throws IOException {
        BridgeHttpClient http = mock(BridgeHttpClient.class);
        String expected = "{\"connectionId\":\"conn-gx\",\"order\":{\"accessionNumber\":\"DEV01260000000000130\","
                + "\"patientId\":\"DEMO-AB-0001\",\"loincCodes\":[\"94500-6\"]}}";
        when(http.post("https://bridge.openelis.org:8443/api/orders", expected, Duration.ofSeconds(40)))
                .thenReturn(new BridgeHttpClient.BridgeResponse(200, "{\"protocol\":\"ASTM\",\"dispatched\":true}"));

        BridgeOrderClient.SendResult result = new BridgeOrderClient(http, "https://bridge.openelis.org:8443/")
                .send("conn-gx", "DEV01260000000000130", "DEMO-AB-0001", List.of("94500-6"));

        assertTrue(result.dispatched());
    }

    @Test
    public void client_bridgeRefusal_carriesTheStatusAndReason() throws IOException {
        BridgeHttpClient http = mock(BridgeHttpClient.class);
        String body = "{\"connectionId\":\"conn-mr\",\"order\":{\"accessionNumber\":\"DEV1\",\"patientId\":\"\","
                + "\"loincCodes\":[\"6690-2\"]}}";
        when(http.post("https://bridge:8443/api/orders", body, Duration.ofSeconds(40)))
                .thenReturn(new BridgeHttpClient.BridgeResponse(400,
                        "{\"dispatched\":false,\"error\":\"Bridge connection conn-mr does not support outbound orders\"}"));

        BridgeOrderClient.SendResult result = new BridgeOrderClient(http, "https://bridge:8443").send("conn-mr", "DEV1",
                null, List.of("6690-2"));

        assertFalse(result.dispatched());
        assertEquals("HTTP 400: Bridge connection conn-mr does not support outbound orders", result.error());
    }

    @Test
    public void client_bridgeUnreachable_isAFailureNotAnException() throws IOException {
        BridgeHttpClient http = mock(BridgeHttpClient.class);
        String body = "{\"connectionId\":\"c\",\"order\":{\"accessionNumber\":\"A\",\"patientId\":\"\","
                + "\"loincCodes\":[\"1-1\"]}}";
        when(http.post("https://bridge:8443/api/orders", body, Duration.ofSeconds(40)))
                .thenThrow(new BridgeHttpClient.BridgeNotPairedException());

        BridgeOrderClient.SendResult result = new BridgeOrderClient(http, "https://bridge:8443").send("c", "A", "",
                List.of("1-1"));

        assertFalse(result.dispatched());
        assertEquals("Bridge unreachable: The Analyzer Bridge is not paired with OpenELIS", result.error());
    }

    // ---- the setting and the sender

    @Test
    public void setting_isOnWhenTheRowIsMissing_offOnlyWhenFalse() {
        SiteInformationService siteInformation = mock(SiteInformationService.class);
        AnalyzerWorklistSettings settings = new AnalyzerWorklistSettings(siteInformation);
        when(siteInformation.getSiteInformationByName(AnalyzerWorklistSettings.ENABLED)).thenReturn(null);
        assertTrue(settings.isEnabled());

        SiteInformation row = new SiteInformation();
        row.setValue("FALSE ");
        when(siteInformation.getSiteInformationByName(AnalyzerWorklistSettings.ENABLED)).thenReturn(row);
        assertFalse(settings.isEnabled());

        row.setValue("true");
        assertTrue(settings.isEnabled());
    }

    @Test
    public void sender_settingOff_sendsNothing() {
        AnalyzerOrderDispatchService dispatchService = mock(AnalyzerOrderDispatchService.class);
        AnalyzerWorklistSettings settings = mock(AnalyzerWorklistSettings.class);
        when(settings.isEnabled()).thenReturn(false);

        new AnalyzerOrderSender(dispatchService, settings).tick();

        org.mockito.Mockito.verifyZeroInteractions(dispatchService);
    }

    @Test
    public void sender_oneRowFailing_stillSendsTheNext() {
        AnalyzerOrderDispatchService dispatchService = mock(AnalyzerOrderDispatchService.class);
        AnalyzerWorklistSettings settings = mock(AnalyzerWorklistSettings.class);
        when(settings.isEnabled()).thenReturn(true);
        AnalyzerOrderSender sender = new AnalyzerOrderSender(dispatchService, settings);
        when(dispatchService.dueIds(org.mockito.ArgumentMatchers.isA(Instant.class),
                org.mockito.ArgumentMatchers.eq(20))).thenReturn(List.of("1", "2"));
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(dispatchService).send("1");

        sender.tick();

        verify(dispatchService).send("2");
    }

    private static BridgeProfileCatalog.ProfileRevision profile(boolean outboundOrders, boolean lisInitiated)
            throws IOException {
        return new BridgeProfileCatalog.ProfileRevision(
                JSON.readTree("{\"capabilities\":{\"outboundOrders\":" + outboundOrders
                        + "},\"communication\":{\"supports_lis_initiated\":" + lisInitiated + "}}"),
                JSON.createObjectNode());
    }

    private static Analyzer analyzer(String profileId, int revision, Analyzer.AnalyzerStatus status) {
        AnalyzerMapping mapping = new AnalyzerMapping();
        mapping.setProfileId(profileId);
        mapping.setProfileRevision(revision);
        Analyzer analyzer = new Analyzer();
        analyzer.setId("5");
        analyzer.setStatus(status);
        analyzer.setBridgeConnectionId("conn-gx");
        analyzer.setMapping(mapping);
        return analyzer;
    }
}
