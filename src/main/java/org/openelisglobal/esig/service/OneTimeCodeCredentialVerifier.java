package org.openelisglobal.esig.service;

import org.openelisglobal.esig.valueholder.AuthMethod;

/**
 * Checks a user's password together with the one-time code from their
 * authenticator app.
 *
 * <p>
 * Optional: a build has a bean of this type only when its signature module can
 * check such codes (for example against Keycloak). A caller that finds no bean
 * must treat codes as uncheckable and fall back to
 * {@link CredentialVerificationService#verifyCredentialsByLoginName(String, String)},
 * never accept a credential it could not check.
 */
public interface OneTimeCodeCredentialVerifier {

    /**
     * @param otp the one-time code, or null; local accounts ignore it
     * @return the authentication method used
     * @throws IllegalArgumentException if the credentials are not accepted
     * @throws RuntimeException         of any other kind if they could not be
     *                                  checked
     */
    AuthMethod verifyCredentialsByLoginName(String loginName, String password, String otp);
}
