package org.openelisglobal.bloodbank.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.openelisglobal.bloodbank.safety.IssueSafetyChecker.SecondCheckOutcome;
import org.openelisglobal.bloodbank.service.SecondPersonVerifier.Verification;
import org.openelisglobal.esig.service.CredentialVerificationService;
import org.openelisglobal.esig.valueholder.AuthMethod;
import org.openelisglobal.systemuser.service.SystemUserService;
import org.openelisglobal.systemuser.valueholder.SystemUser;

/**
 * Runs against either form of the e-signature credential check: the
 * two-argument one, or the three-argument one (with the authenticator code)
 * when the build includes Keycloak support for signatures.
 */
@RunWith(MockitoJUnitRunner.class)
public class SecondPersonVerifierImplTest {

    private static final String ISSUER = "10";
    private static final Method WITH_OTP = otpOverload();

    @Mock
    private CredentialVerificationService credentials;
    @Mock
    private SystemUserService systemUserService;

    @InjectMocks
    private SecondPersonVerifierImpl verifier;

    private static Method otpOverload() {
        try {
            return CredentialVerificationService.class.getMethod("verifyCredentialsByLoginName", String.class,
                    String.class, String.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static SystemUser user(String id, String login, String active) {
        SystemUser user = new SystemUser();
        user.setId(id);
        user.setLoginName(login);
        user.setIsActive(active);
        return user;
    }

    /**
     * The credential check, as the verifier will call it, on this mock (stubbing or
     * verifying).
     */
    private static Object ask(CredentialVerificationService target, String login, String password, String otp)
            throws Exception {
        if (WITH_OTP == null) {
            return target.verifyCredentialsByLoginName(login, password);
        }
        try {
            return WITH_OTP.invoke(target, login, password, otp);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    public void aDifferentActiveUserWithTheRightPasswordIsVerified() throws Exception {
        when(systemUserService.getDataForLoginUser("checker")).thenReturn(user("11", "checker", "Y"));
        when(ask(credentials, "checker", "pw", null)).thenReturn(AuthMethod.LOCAL);

        Verification result = verifier.verify(ISSUER, " checker ", "pw", null);

        assertEquals(SecondCheckOutcome.VERIFIED, result.outcome());
        assertEquals(Integer.valueOf(11), result.verifiedUserId());
        ask(verify(credentials), "checker", "pw", null);
    }

    @Test
    public void theAuthenticatorCodeIsPassedOnWhenTheSignatureModuleChecksIt() throws Exception {
        Assume.assumeTrue("only with Keycloak support for signatures", WITH_OTP != null);
        when(systemUserService.getDataForLoginUser("checker")).thenReturn(user("11", "checker", "Y"));
        when(ask(credentials, "checker", "pw", "123456")).thenReturn(AuthMethod.KEYCLOAK);

        assertEquals(SecondCheckOutcome.VERIFIED, verifier.verify(ISSUER, "checker", "pw", "123456").outcome());
        ask(verify(credentials), "checker", "pw", "123456");
    }

    @Test
    public void theIssuerCannotBeTheirOwnSecondPerson() {
        when(systemUserService.getDataForLoginUser("me")).thenReturn(user(ISSUER, "me", "Y"));

        assertEquals(SecondCheckOutcome.SAME_USER, verifier.verify(ISSUER, "me", "pw", null).outcome());
        verifyZeroInteractions(credentials);
    }

    @Test
    public void anInactiveOrUnknownUserIsRefusedWithoutAskingForThePassword() {
        when(systemUserService.getDataForLoginUser("gone")).thenReturn(user("12", "gone", "N"));
        when(systemUserService.getDataForLoginUser("nobody")).thenReturn(null);

        assertEquals(SecondCheckOutcome.INACTIVE_USER, verifier.verify(ISSUER, "gone", "pw", null).outcome());
        assertEquals(SecondCheckOutcome.UNKNOWN_USER, verifier.verify(ISSUER, "nobody", "pw", null).outcome());
        verifyZeroInteractions(credentials);
    }

    @Test
    public void aWrongPasswordIsInvalid() throws Exception {
        when(systemUserService.getDataForLoginUser("checker")).thenReturn(user("11", "checker", "Y"));
        when(ask(credentials, "checker", "wrong", null)).thenThrow(new IllegalArgumentException("Invalid credentials"));

        Verification result = verifier.verify(ISSUER, "checker", "wrong", null);

        assertEquals(SecondCheckOutcome.INVALID_CREDENTIALS, result.outcome());
        assertNull(result.verifiedUserId());
    }

    @Test
    public void verificationThatCannotBeMadeFailsClosed() throws Exception {
        when(systemUserService.getDataForLoginUser("checker")).thenReturn(user("11", "checker", "Y"));
        when(ask(credentials, "checker", "pw", null))
                .thenThrow(new UnsupportedOperationException("Keycloak verification not implemented"));
        assertEquals(SecondCheckOutcome.UNAVAILABLE, verifier.verify(ISSUER, "checker", "pw", null).outcome());

        when(ask(credentials, "checker", "pw2", null)).thenThrow(new IllegalStateException("Authentication error"));
        assertEquals(SecondCheckOutcome.UNAVAILABLE, verifier.verify(ISSUER, "checker", "pw2", null).outcome());
    }

    @Test
    public void aUserLookupFailureFailsClosed() {
        when(systemUserService.getDataForLoginUser("checker")).thenThrow(new IllegalStateException("db down"));

        assertEquals(SecondCheckOutcome.UNAVAILABLE, verifier.verify(ISSUER, "checker", "pw", null).outcome());
        verifyZeroInteractions(credentials);
    }

    @Test
    public void nothingEnteredIsNotProvided() {
        assertEquals(SecondCheckOutcome.NOT_PROVIDED, verifier.verify(ISSUER, "", "pw", null).outcome());
        assertEquals(SecondCheckOutcome.NOT_PROVIDED, verifier.verify(ISSUER, "checker", "", null).outcome());
        assertEquals(SecondCheckOutcome.NOT_PROVIDED, verifier.verify(ISSUER, null, null, null).outcome());
        verifyZeroInteractions(systemUserService, credentials);
    }
}
