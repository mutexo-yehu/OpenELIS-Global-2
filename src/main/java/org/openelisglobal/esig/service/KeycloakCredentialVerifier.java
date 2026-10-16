package org.openelisglobal.esig.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.openelisglobal.common.log.LogEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Checks an e-signature's password and authenticator code with Keycloak, for
 * users who sign in through Keycloak and so have no OpenELIS password.
 *
 * <p>
 * It asks Keycloak's token endpoint for a token with the OpenID Connect
 * password grant, on a client kept for this purpose only: confidential (it has
 * a secret), direct access grants on, every other flow off. Keycloak applies
 * the realm's own rules to the attempt: the password, the user's one-time code
 * when they have one, disabled accounts and brute-force lockout. A token is
 * only proof that the check passed; it is neither used nor kept.
 *
 * <p>
 * Settings: {@code org.openelisglobal.esig.keycloak.tokenUrl} (the realm's
 * {@code .../protocol/openid-connect/token}),
 * {@code org.openelisglobal.esig.keycloak.clientId} (default
 * {@code openelis-esig}) and
 * {@code org.openelisglobal.esig.keycloak.clientSecret}.
 */
@Component
public class KeycloakCredentialVerifier {

    /** One call to the token endpoint; a seam for tests. */
    interface TokenEndpoint {
        Response post(URI uri, String form) throws IOException, InterruptedException;
    }

    record Response(int status, String body) {
    }

    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;
    private final TokenEndpoint endpoint;

    @Autowired
    public KeycloakCredentialVerifier(@Value("${org.openelisglobal.esig.keycloak.tokenUrl:}") String tokenUrl,
            @Value("${org.openelisglobal.esig.keycloak.clientId:openelis-esig}") String clientId,
            @Value("${org.openelisglobal.esig.keycloak.clientSecret:}") String clientSecret) {
        this(tokenUrl, clientId, clientSecret, httpEndpoint());
    }

    KeycloakCredentialVerifier(String tokenUrl, String clientId, String clientSecret, TokenEndpoint endpoint) {
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.endpoint = endpoint;
    }

    /** True once the token endpoint and the client's secret are set. */
    public boolean isConfigured() {
        return !isBlank(tokenUrl) && !isBlank(clientId) && !isBlank(clientSecret);
    }

    /**
     * Returns when Keycloak accepts the login name, password and code.
     *
     * @throws IllegalArgumentException             the credentials were refused
     *                                              (wrong password or code,
     *                                              disabled or locked account)
     * @throws EsigVerificationUnavailableException Keycloak couldn't be asked, or
     *                                              refused this client
     */
    public void verify(String loginName, String password, String otp) {
        if (!isConfigured()) {
            throw new EsigVerificationUnavailableException("Keycloak verification of electronic signatures is not "
                    + "configured (org.openelisglobal.esig.keycloak.tokenUrl and clientSecret)");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "password");
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        form.put("username", loginName);
        form.put("password", password);
        if (!isBlank(otp)) {
            form.put("totp", otp.trim());
        }
        Response response;
        try {
            response = endpoint.post(URI.create(tokenUrl), encode(form));
        } catch (IOException e) {
            throw new EsigVerificationUnavailableException("Keycloak could not be reached to verify a signature", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EsigVerificationUnavailableException("Signature verification was interrupted", e);
        }
        if (response.status() == 200) {
            return;
        }
        String body = response.body() == null ? "" : response.body();
        // Keycloak answers a wrong secret or a client without direct grants this way:
        // the signer did nothing wrong
        if (body.contains("\"invalid_client\"") || body.contains("\"unauthorized_client\"")) {
            LogEvent.logError(getClass().getSimpleName(), "verify",
                    "Keycloak refused the e-signature client " + clientId + ": HTTP " + response.status());
            throw new EsigVerificationUnavailableException("Keycloak refused the signature verification client");
        }
        if (response.status() == 400 || response.status() == 401) {
            throw new IllegalArgumentException("Invalid credentials");
        }
        throw new EsigVerificationUnavailableException(
                "Keycloak answered HTTP " + response.status() + " to a signature verification");
    }

    private static String encode(Map<String, String> form) {
        return form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static TokenEndpoint httpEndpoint() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return (uri, form) -> {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        };
    }
}
