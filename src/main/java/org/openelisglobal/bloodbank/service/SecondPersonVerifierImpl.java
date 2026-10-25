package org.openelisglobal.bloodbank.service;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.openelisglobal.bloodbank.safety.IssueSafetyChecker.SecondCheckOutcome;
import org.openelisglobal.common.log.LogEvent;
import org.openelisglobal.esig.service.CredentialVerificationService;
import org.openelisglobal.systemuser.service.SystemUserService;
import org.openelisglobal.systemuser.valueholder.SystemUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Uses the e-signature module's {@link CredentialVerificationService}, so the
 * second check accepts exactly what an electronic signature accepts.
 *
 * <p>
 * Runs outside the caller's transaction: a refused credential must not mark the
 * issuing transaction for rollback, and a call to the identity provider must
 * not hold the unit's row lock.
 */
@Service
public class SecondPersonVerifierImpl implements SecondPersonVerifier {

    /**
     * {@code verifyCredentialsByLoginName(login, password, otp)}, when the
     * e-signature module has Keycloak support (it then checks the authenticator
     * code); null on builds where only the two-argument form exists, which checks
     * local passwords and has no code to check.
     */
    private static final Method WITH_OTP = findOtpOverload();

    @Autowired
    private CredentialVerificationService credentialVerificationService;

    @Autowired
    private SystemUserService systemUserService;

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Verification verify(String issuingUserId, String login, String password, String otp) {
        if (login == null || login.isBlank() || password == null || password.isEmpty()) {
            return new Verification(SecondCheckOutcome.NOT_PROVIDED, null);
        }
        SystemUser user;
        try {
            user = systemUserService.getDataForLoginUser(login.trim());
        } catch (RuntimeException e) {
            LogEvent.logError(e);
            return new Verification(SecondCheckOutcome.UNAVAILABLE, null);
        }
        if (user == null || user.getId() == null) {
            return new Verification(SecondCheckOutcome.UNKNOWN_USER, null);
        }
        if (user.getId().equals(issuingUserId)) {
            return new Verification(SecondCheckOutcome.SAME_USER, null);
        }
        if (!"Y".equals(user.getIsActive())) {
            return new Verification(SecondCheckOutcome.INACTIVE_USER, null);
        }
        try {
            check(user.getLoginName(), password, otp);
        } catch (IllegalArgumentException e) {
            return new Verification(SecondCheckOutcome.INVALID_CREDENTIALS, null);
        } catch (RuntimeException e) {
            // identity provider unreachable, not configured, or anything else: fail closed
            LogEvent.logWarn(getClass().getSimpleName(), "verify",
                    "second-person check unavailable: " + e.getClass().getSimpleName());
            return new Verification(SecondCheckOutcome.UNAVAILABLE, null);
        }
        return new Verification(SecondCheckOutcome.VERIFIED, Integer.valueOf(user.getId()));
    }

    private void check(String login, String password, String otp) {
        if (WITH_OTP == null) {
            credentialVerificationService.verifyCredentialsByLoginName(login, password);
            return;
        }
        try {
            WITH_OTP.invoke(credentialVerificationService, login, password, otp);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("credential verification failed", cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("credential verification not callable", e);
        }
    }

    private static Method findOtpOverload() {
        try {
            return CredentialVerificationService.class.getMethod("verifyCredentialsByLoginName", String.class,
                    String.class, String.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
