package org.openelisglobal.bloodbank.service;

import org.openelisglobal.bloodbank.safety.IssueSafetyChecker.SecondCheckOutcome;
import org.openelisglobal.common.log.LogEvent;
import org.openelisglobal.esig.service.CredentialVerificationService;
import org.openelisglobal.esig.service.OneTimeCodeCredentialVerifier;
import org.openelisglobal.systemuser.service.SystemUserService;
import org.openelisglobal.systemuser.valueholder.SystemUser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Uses the e-signature module's credential check, so the second check accepts
 * exactly what an electronic signature accepts: with the authenticator code
 * when the build has a {@link OneTimeCodeCredentialVerifier}, else the
 * password-only {@link CredentialVerificationService} (which refuses, and so
 * fails closed, where it cannot check a site's accounts).
 *
 * <p>
 * Runs outside the caller's transaction: a refused credential must not mark the
 * issuing transaction for rollback, and a call to the identity provider must
 * not hold the unit's row lock.
 */
@Service
public class SecondPersonVerifierImpl implements SecondPersonVerifier {

    private final CredentialVerificationService credentialVerificationService;
    /** null when the build cannot check authenticator codes */
    private final OneTimeCodeCredentialVerifier oneTimeCodeVerifier;
    private final SystemUserService systemUserService;

    @Autowired
    public SecondPersonVerifierImpl(CredentialVerificationService credentialVerificationService,
            ObjectProvider<OneTimeCodeCredentialVerifier> oneTimeCodeVerifier, SystemUserService systemUserService) {
        // getIfAvailable: none gives null; more than one fails at startup, never a
        // guess
        this(credentialVerificationService, oneTimeCodeVerifier.getIfAvailable(), systemUserService);
    }

    SecondPersonVerifierImpl(CredentialVerificationService credentialVerificationService,
            OneTimeCodeCredentialVerifier oneTimeCodeVerifier, SystemUserService systemUserService) {
        this.credentialVerificationService = credentialVerificationService;
        this.oneTimeCodeVerifier = oneTimeCodeVerifier;
        this.systemUserService = systemUserService;
    }

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
            if (oneTimeCodeVerifier != null) {
                oneTimeCodeVerifier.verifyCredentialsByLoginName(user.getLoginName(), password, otp);
            } else {
                credentialVerificationService.verifyCredentialsByLoginName(user.getLoginName(), password);
            }
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
}
