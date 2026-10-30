package org.openelisglobal.analyzer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Sends one order to an analyser through the Bridge's {@code POST /api/orders}:
 * the Bridge connection, the lab number, the patient and the LOINC codes. The
 * Bridge translates the codes with the analyser's profile and sends ASTM order
 * records or HL7 ORM. Called over the paired, certificate-authenticated
 * {@link BridgeHttpClient}, like the other Bridge clients.
 */
@Service
public class BridgeOrderClient {

    // the Bridge waits up to 30 s for the analyser
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(40);
    private static final int MAX_ERROR_LENGTH = 500;

    public record SendResult(boolean dispatched, String error) {
    }

    private final BridgeHttpClient httpClient;
    private final String bridgeBaseUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BridgeOrderClient(BridgeHttpClient httpClient, @Value("${analyzer.bridge.url:}") String bridgeBaseUrl) {
        this.httpClient = httpClient;
        this.bridgeBaseUrl = bridgeBaseUrl == null ? "" : bridgeBaseUrl.trim().replaceAll("/+$", "");
    }

    public SendResult send(String connectionId, String accessionNumber, String patientId, List<String> loincCodes) {
        if (bridgeBaseUrl.isEmpty()) {
            return new SendResult(false, "Bridge URL is not configured");
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("connectionId", connectionId);
        ObjectNode order = body.putObject("order");
        order.put("accessionNumber", accessionNumber);
        order.put("patientId", patientId == null ? "" : patientId);
        loincCodes.forEach(order.putArray("loincCodes")::add);
        BridgeHttpClient.BridgeResponse response;
        try {
            response = httpClient.post(bridgeBaseUrl + "/api/orders", body.toString(), REQUEST_TIMEOUT);
        } catch (IOException e) {
            return new SendResult(false, truncate("Bridge unreachable: " + e.getMessage()));
        }
        JsonNode answer = parse(response.body);
        if (response.isSuccess() && answer.path("dispatched").asBoolean(false)) {
            return new SendResult(true, null);
        }
        String reason = answer.path("error").asText("");
        if (reason.isEmpty()) {
            reason = response.body == null ? "" : response.body.strip();
        }
        return new SendResult(false, truncate("HTTP " + response.status + (reason.isEmpty() ? "" : ": " + reason)));
    }

    private JsonNode parse(String text) {
        try {
            return text == null || text.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(text);
        } catch (IOException e) {
            return objectMapper.createObjectNode();
        }
    }

    private static String truncate(String text) {
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }
}
