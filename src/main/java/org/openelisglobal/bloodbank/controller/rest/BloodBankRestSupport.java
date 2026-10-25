package org.openelisglobal.bloodbank.controller.rest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.openelisglobal.bloodbank.service.BloodBankException;
import org.openelisglobal.bloodbank.service.BloodBankException.Kind;
import org.openelisglobal.common.rest.BaseRestController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * What the blood bank controllers share: one error body, {@code {errorCode,
 * params, checks}}, and the reading of enum query parameters.
 */
public abstract class BloodBankRestSupport extends BaseRestController {

    @ExceptionHandler(BloodBankException.class)
    public ResponseEntity<Map<String, Object>> refused(BloodBankException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorCode", e.getErrorCode());
        body.put("params", e.getParams());
        if (!e.getChecks().isEmpty()) {
            body.put("checks", e.getChecks());
        }
        return ResponseEntity.status(status(e.getKind())).body(body);
    }

    static HttpStatus status(Kind kind) {
        return switch (kind) {
        case INVALID -> HttpStatus.BAD_REQUEST;
        case NOT_FOUND -> HttpStatus.NOT_FOUND;
        case CONFLICT -> HttpStatus.CONFLICT;
        case UNSAFE -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }

    /**
     * An optional enum parameter.
     *
     * @throws BloodBankException INVALID when present but not a value of the enum
     */
    protected static <E extends Enum<E>> E enumParam(Class<E> type, String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BloodBankException(Kind.INVALID, "bloodbank.error.parameter", name, value);
        }
    }

    /** Repeated or comma-separated enum values. */
    protected static <E extends Enum<E>> List<E> enumListParam(Class<E> type, String name, List<String> values) {
        List<E> result = new ArrayList<>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            for (String part : value.split(",")) {
                E parsed = enumParam(type, name, part);
                if (parsed != null) {
                    result.add(parsed);
                }
            }
        }
        return result;
    }
}
