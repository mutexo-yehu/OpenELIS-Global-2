package org.openelisglobal.bloodbank.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.openelisglobal.bloodbank.safety.IssueSafetyChecker.SecondCheckOutcome;
import org.openelisglobal.bloodbank.service.SecondPersonVerifier.Verification;
import org.openelisglobal.esig.service.CredentialVerificationService;
import org.openelisglobal.esig.service.OneTimeCodeCredentialVerifier;
import org.openelisglobal.esig.valueholder.AuthMethod;
import org.openelisglobal.systemuser.service.SystemUserService;
import org.openelisglobal.systemuser.valueholder.SystemUser;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Both forms of the credential check: password only (no
 * {@link OneTimeCodeCredentialVerifier} in the build) and password plus
 * authenticator code (one is present).
 */
@RunWith(MockitoJUnitRunner.class)
public class SecondPersonVerifierImplTest {

    private static final String ISSUER = "10";

    @Mock
    private CredentialVerificationService credentials;
    @Mock
    private OneTimeCodeCredentialVerifier codes;
    @Mock
    private SystemUserService systemUserService;
    @Mock
    private ObjectProvider<OneTimeCodeCredentialVerifier> codesProvider;

    /** a build without a code check */
    private SecondPersonVerifierImpl passwordOnly;
    /** a build with one */
    private SecondPersonVerifierImpl withCodes;

    @Before
    public void setUp() {
        passwordOnly = new SecondPersonVerifierImpl(credentials, (OneTimeCodeCredentialVerifier) null,
                systemUserService);
        withCodes = new SecondPersonVerifierImpl(credentials, codes, systemUserService);
    }

    private static SystemUser user(String id, String login, String active) {
        SystemUser user = new SystemUser();
        user.setId(id);
        user.setLoginName(login);
        user.setIsActive(active);
        return user;
    }

    private void checkerIs(SystemUser user) {
        when(systemUserService.getDataForLoginUser("checker")).thenReturn(user);
    }

    @Test
    public void aDifferentActiveUserWithTheRightPasswordIsVerified() {
        checkerIs(user("11", "checker", "Y"));
        when(credentials.verifyCredentialsByLoginName("checker", "pw")).thenReturn(AuthMethod.LOCAL);

        Verification result = passwordOnly.verify(ISSUER, " checker ", "pw", null);

        assertEquals(SecondCheckOutcome.VERIFIED, result.outcome());
        assertEquals(Integer.valueOf(11), result.verifiedUserId());
        verify(credentials).verifyCredentialsByLoginName("checker", "pw");
    }

    @Test
    public void theAuthenticatorCodeIsCheckedWhenTheBuildCanCheckIt() {
        checkerIs(user("11", "checker", "Y"));
        when(codes.verifyCredentialsByLoginName("checker", "pw", "123456")).thenReturn(AuthMethod.KEYCLOAK);

        assertEquals(SecondCheckOutcome.VERIFIED, withCodes.verify(ISSUER, "checker", "pw", "123456").outcome());
        verify(codes).verifyCredentialsByLoginName("checker", "pw", "123456");
        // the password-only form is not asked as well (or instead)
        verifyZeroInteractions(credentials);
    }

    @Test
    public void aWrongCodeIsInvalid() {
        checkerIs(user("11", "checker", "Y"));
        when(codes.verifyCredentialsByLoginName("checker", "pw", "000000"))
                .thenThrow(new IllegalArgumentException("Invalid credentials"));

        Verification result = withCodes.verify(ISSUER, "checker", "pw", "000000");

        assertEquals(SecondCheckOutcome.INVALID_CREDENTIALS, result.outcome());
        assertNull(result.verifiedUserId());
    }

    @Test
    public void aCodeCheckThatCannotBeMadeFailsClosed() {
        checkerIs(user("11", "checker", "Y"));
        when(codes.verifyCredentialsByLoginName("checker", "pw", "123456"))
                .thenThrow(new IllegalStateException("identity provider unreachable"));

        assertEquals(SecondCheckOutcome.UNAVAILABLE, withCodes.verify(ISSUER, "checker", "pw", "123456").outcome());
    }

    @Test
    public void theSpringConstructorUsesTheCodeCheckWhenTheBuildHasOne() {
        when(codesProvider.getIfAvailable()).thenReturn(codes);
        SecondPersonVerifierImpl wired = new SecondPersonVerifierImpl(credentials, codesProvider, systemUserService);
        checkerIs(user("11", "checker", "Y"));
        when(codes.verifyCredentialsByLoginName("checker", "pw", "123456")).thenReturn(AuthMethod.KEYCLOAK);

        assertEquals(SecondCheckOutcome.VERIFIED, wired.verify(ISSUER, "checker", "pw", "123456").outcome());
        verifyZeroInteractions(credentials);
    }

    @Test
    public void theSpringConstructorFallsBackToThePasswordCheckWithoutOne() {
        when(codesProvider.getIfAvailable()).thenReturn(null);
        SecondPersonVerifierImpl wired = new SecondPersonVerifierImpl(credentials, codesProvider, systemUserService);
        checkerIs(user("11", "checker", "Y"));
        when(credentials.verifyCredentialsByLoginName("checker", "pw"))
                .thenThrow(new UnsupportedOperationException("Keycloak verification not implemented"));

        // a Keycloak site on a build without the code check: refused, not waved through
        assertEquals(SecondCheckOutcome.UNAVAILABLE, wired.verify(ISSUER, "checker", "pw", "123456").outcome());
    }

    @Test
    public void theIssuerCannotBeTheirOwnSecondPerson() {
        when(systemUserService.getDataForLoginUser("me")).thenReturn(user(ISSUER, "me", "Y"));

        assertEquals(SecondCheckOutcome.SAME_USER, withCodes.verify(ISSUER, "me", "pw", null).outcome());
        verifyZeroInteractions(credentials, codes);
    }

    @Test
    public void anInactiveOrUnknownUserIsRefusedWithoutAskingForThePassword() {
        when(systemUserService.getDataForLoginUser("gone")).thenReturn(user("12", "gone", "N"));
        when(systemUserService.getDataForLoginUser("nobody")).thenReturn(null);

        assertEquals(SecondCheckOutcome.INACTIVE_USER, withCodes.verify(ISSUER, "gone", "pw", null).outcome());
        assertEquals(SecondCheckOutcome.UNKNOWN_USER, passwordOnly.verify(ISSUER, "nobody", "pw", null).outcome());
        verifyZeroInteractions(credentials, codes);
    }

    @Test
    public void aWrongPasswordIsInvalid() {
        checkerIs(user("11", "checker", "Y"));
        when(credentials.verifyCredentialsByLoginName("checker", "wrong"))
                .thenThrow(new IllegalArgumentException("Invalid credentials"));

        Verification result = passwordOnly.verify(ISSUER, "checker", "wrong", null);

        assertEquals(SecondCheckOutcome.INVALID_CREDENTIALS, result.outcome());
        assertNull(result.verifiedUserId());
    }

    @Test
    public void verificationThatCannotBeMadeFailsClosed() {
        checkerIs(user("11", "checker", "Y"));
        when(credentials.verifyCredentialsByLoginName("checker", "pw"))
                .thenThrow(new UnsupportedOperationException("Keycloak verification not implemented"));
        assertEquals(SecondCheckOutcome.UNAVAILABLE, passwordOnly.verify(ISSUER, "checker", "pw", null).outcome());

        when(credentials.verifyCredentialsByLoginName("checker", "pw2"))
                .thenThrow(new IllegalStateException("Authentication error"));
        assertEquals(SecondCheckOutcome.UNAVAILABLE, passwordOnly.verify(ISSUER, "checker", "pw2", null).outcome());
    }

    @Test
    public void aUserLookupFailureFailsClosed() {
        when(systemUserService.getDataForLoginUser("checker")).thenThrow(new IllegalStateException("db down"));

        assertEquals(SecondCheckOutcome.UNAVAILABLE, withCodes.verify(ISSUER, "checker", "pw", null).outcome());
        verifyZeroInteractions(credentials, codes);
    }

    @Test
    public void nothingEnteredIsNotProvided() {
        assertEquals(SecondCheckOutcome.NOT_PROVIDED, withCodes.verify(ISSUER, "", "pw", null).outcome());
        assertEquals(SecondCheckOutcome.NOT_PROVIDED, withCodes.verify(ISSUER, "checker", "", null).outcome());
        assertEquals(SecondCheckOutcome.NOT_PROVIDED, withCodes.verify(ISSUER, null, null, null).outcome());
        verifyZeroInteractions(systemUserService, credentials, codes);
    }
}
