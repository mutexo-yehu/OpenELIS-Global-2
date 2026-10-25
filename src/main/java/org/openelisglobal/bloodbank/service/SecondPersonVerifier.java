package org.openelisglobal.bloodbank.service;

import org.openelisglobal.bloodbank.safety.IssueSafetyChecker.SecondCheckOutcome;

/**
 * Re-authenticates the second person who checks a unit at issue: a different,
 * active user, with their password and (when their account has one) their
 * authenticator code. Fails closed: if the check cannot be made, the outcome is
 * UNAVAILABLE, never VERIFIED.
 */
public interface SecondPersonVerifier {

    /**
     * @param verifiedUserId the second person's system user id when VERIFIED, else
     *                       null
     */
    record Verification(SecondCheckOutcome outcome, Integer verifiedUserId) {
    }

    Verification verify(String issuingUserId, String login, String password, String otp);
}
