package org.openelisglobal.esig.service;

/**
 * The signer's credentials could not be checked: the identity provider is
 * unreachable, refused the verification client, or isn't configured. Not a
 * wrong password; the signer should not be told their credentials are invalid.
 */
public class EsigVerificationUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EsigVerificationUnavailableException(String message) {
        super(message);
    }

    public EsigVerificationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
