package org.openelisglobal.bloodbank.service;

import java.util.List;
import org.openelisglobal.bloodbank.safety.SafetyCheck;

/**
 * A request the blood bank refuses, with a react-intl key the frontend
 * translates. Thrown before anything is written.
 */
public class BloodBankException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why, which decides the HTTP status. */
    public enum Kind {
        /** 400: the request itself is incomplete or malformed. */
        INVALID,
        /** 404: the unit, reservation, patient or type does not exist. */
        NOT_FOUND,
        /** 409: the unit's state does not allow it (or a duplicate). */
        CONFLICT,
        /** 422: a safety check failed; the checks are attached. */
        UNSAFE
    }

    private final Kind kind;
    private final String errorCode;
    private final List<String> params;
    private final transient List<SafetyCheck> checks;

    public BloodBankException(Kind kind, String errorCode, String... params) {
        this(kind, errorCode, List.of(), params);
    }

    public BloodBankException(Kind kind, String errorCode, List<SafetyCheck> checks, String... params) {
        super(errorCode + (params.length == 0 ? "" : " " + String.join(", ", params)));
        this.kind = kind;
        this.errorCode = errorCode;
        this.params = List.of(params);
        this.checks = checks == null ? List.of() : List.copyOf(checks);
    }

    public Kind getKind() {
        return kind;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public List<String> getParams() {
        return params;
    }

    public List<SafetyCheck> getChecks() {
        return checks;
    }
}
