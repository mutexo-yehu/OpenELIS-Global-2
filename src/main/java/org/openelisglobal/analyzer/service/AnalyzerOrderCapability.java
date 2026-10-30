package org.openelisglobal.analyzer.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.openelisglobal.analyzer.valueholder.Analyzer;
import org.openelisglobal.analyzer.valueholder.Analyzer.AnalyzerStatus;
import org.openelisglobal.analyzer.valueholder.AnalyzerMapping;
import org.openelisglobal.common.log.LogEvent;
import org.springframework.stereotype.Service;

/**
 * Whether an analyser takes orders from OpenELIS: it is active, has a Bridge
 * connection, and the Bridge profile its mapping is pinned to declares outbound
 * orders ({@code capabilities.outboundOrders} and
 * {@code communication.supports_lis_initiated}), the same profile test the
 * Bridge applies before it dispatches. A profile revision never changes, so
 * each answer is kept.
 */
@Service
public class AnalyzerOrderCapability {

    private final BridgeProfileCatalogService profileCatalogService;
    private final Map<String, Boolean> byProfileRevision = new ConcurrentHashMap<>();

    public AnalyzerOrderCapability(BridgeProfileCatalogService profileCatalogService) {
        this.profileCatalogService = profileCatalogService;
    }

    public boolean acceptsOrders(Analyzer analyzer) {
        if (analyzer == null || analyzer.getStatus() != AnalyzerStatus.ACTIVE
                || analyzer.getBridgeConnectionId() == null || analyzer.getBridgeConnectionId().isBlank()) {
            return false;
        }
        AnalyzerMapping mapping = analyzer.getMapping();
        if (mapping == null || mapping.getProfileId() == null) {
            return false;
        }
        String key = mapping.getProfileId() + "@" + mapping.getProfileRevision();
        Boolean known = byProfileRevision.get(key);
        if (known != null) {
            return known;
        }
        try {
            JsonNode profile = profileCatalogService.getProfile(mapping.getProfileId(), mapping.getProfileRevision())
                    .profile();
            boolean accepts = profile.path("capabilities").path("outboundOrders").asBoolean(false)
                    && profile.path("communication").path("supports_lis_initiated").asBoolean(false);
            byProfileRevision.put(key, accepts);
            return accepts;
        } catch (RuntimeException e) {
            // not remembered: asked again next time
            LogEvent.logWarn(getClass().getSimpleName(), "acceptsOrders",
                    "cannot read Bridge profile " + key + ": " + e.getMessage());
            return false;
        }
    }
}
