package org.openelisglobal.esig.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class KeycloakCredentialVerifierTest {

    private static final String TOKEN_URL = "http://keycloak.test:8080/auth/realms/OpenELIS/protocol/openid-connect/token";

    /** Records each call and answers with the given status and body. */
    private static final class FakeEndpoint implements KeycloakCredentialVerifier.TokenEndpoint {
        final List<String> forms = new ArrayList<>();
        final List<URI> uris = new ArrayList<>();
        private final int status;
        private final String body;
        private final IOException failure;

        FakeEndpoint(int status, String body) {
            this.status = status;
            this.body = body;
            this.failure = null;
        }

        FakeEndpoint(IOException failure) {
            this.status = 0;
            this.body = null;
            this.failure = failure;
        }

        @Override
        public KeycloakCredentialVerifier.Response post(URI uri, String form) throws IOException {
            uris.add(uri);
            forms.add(form);
            if (failure != null) {
                throw failure;
            }
            return new KeycloakCredentialVerifier.Response(status, body);
        }
    }

    private static KeycloakCredentialVerifier verifier(FakeEndpoint endpoint) {
        return new KeycloakCredentialVerifier(TOKEN_URL, "openelis-esig", "s3cret", endpoint);
    }

    @Test
    public void acceptedCredentialsPassWithPasswordAndCodeSentToTheTokenEndpoint() {
        FakeEndpoint endpoint = new FakeEndpoint(200, "{\"access_token\":\"x\"}");

        verifier(endpoint).verify("sci1", "pa ss&word", " 123456 ");

        assertEquals(URI.create(TOKEN_URL), endpoint.uris.get(0));
        assertEquals("grant_type=password&client_id=openelis-esig&client_secret=s3cret&username=sci1"
                + "&password=pa+ss%26word&totp=123456", endpoint.forms.get(0));
    }

    @Test
    public void withoutACodeNoTotpIsSent() {
        FakeEndpoint endpoint = new FakeEndpoint(200, "{}");

        verifier(endpoint).verify("sci1", "secret", null);

        assertFalse(endpoint.forms.get(0), endpoint.forms.get(0).contains("totp"));
    }

    @Test
    public void aWrongPasswordIsInvalidCredentials() {
        FakeEndpoint endpoint = new FakeEndpoint(401,
                "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid user credentials\"}");

        assertThrows(IllegalArgumentException.class, () -> verifier(endpoint).verify("sci1", "wrong", "123456"));
    }

    @Test
    public void aMissingOrWrongCodeIsInvalidCredentials() {
        FakeEndpoint endpoint = new FakeEndpoint(400,
                "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid user credentials\"}");

        assertThrows(IllegalArgumentException.class, () -> verifier(endpoint).verify("sci1", "secret", null));
    }

    @Test
    public void aRefusedClientIsUnavailableNotTheSignersFault() {
        FakeEndpoint endpoint = new FakeEndpoint(401,
                "{\"error\":\"invalid_client\",\"error_description\":\"Invalid client credentials\"}");

        assertThrows(EsigVerificationUnavailableException.class,
                () -> verifier(endpoint).verify("sci1", "secret", "123456"));
    }

    @Test
    public void keycloakDownIsUnavailable() {
        FakeEndpoint endpoint = new FakeEndpoint(new IOException("Connection refused"));

        assertThrows(EsigVerificationUnavailableException.class,
                () -> verifier(endpoint).verify("sci1", "secret", "123456"));
    }

    @Test
    public void aServerErrorIsUnavailable() {
        FakeEndpoint endpoint = new FakeEndpoint(503, "");

        assertThrows(EsigVerificationUnavailableException.class,
                () -> verifier(endpoint).verify("sci1", "secret", "123456"));
    }

    @Test
    public void withoutASecretNothingIsSentAndVerificationIsUnavailable() {
        FakeEndpoint endpoint = new FakeEndpoint(200, "{}");
        KeycloakCredentialVerifier unconfigured = new KeycloakCredentialVerifier(TOKEN_URL, "openelis-esig", "",
                endpoint);

        assertFalse(unconfigured.isConfigured());
        assertThrows(EsigVerificationUnavailableException.class, () -> unconfigured.verify("sci1", "secret", "1"));
        assertTrue(endpoint.forms.isEmpty());
    }
}
