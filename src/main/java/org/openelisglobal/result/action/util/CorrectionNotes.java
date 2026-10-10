package org.openelisglobal.result.action.util;

import java.text.MessageFormat;
import java.util.List;
import org.apache.commons.validator.GenericValidator;
import org.openelisglobal.analysis.valueholder.Analysis;
import org.openelisglobal.common.log.LogEvent;
import org.openelisglobal.internationalization.MessageUtil;
import org.openelisglobal.note.valueholder.Note;
import org.openelisglobal.result.service.ResultService;
import org.openelisglobal.result.valueholder.Result;
import org.openelisglobal.spring.util.SpringContext;
import org.openelisglobal.systemuser.service.SystemUserService;
import org.openelisglobal.systemuser.valueholder.SystemUser;

/**
 * Amended-report traceability (ISO 15189): when a result is corrected after its
 * patient report went out, the report has to show what was reported before and
 * who changed it, on every copy.
 *
 * <p>
 * The existing "Result corrected" note (note.corrected.result) stays exactly as
 * it is: the Amendment quality indicator counts corrections by its text. The
 * previous value goes in a second external note beside it, "Previously
 * reported: 250 10^3/µl; corrected by Ann Smith".
 */
public final class CorrectionNotes {

    static final String CORRECTED_KEY = "note.corrected.result";
    static final String PREVIOUS_KEY = "note.corrected.previous";
    static final String PREVIOUS_DEFAULT = "Previously reported: {0}; corrected by {1}";

    private CorrectionNotes() {
    }

    /**
     * The analysis's current value with units, read before a save replaces it.
     * Empty for an analysis not yet saved or without a value.
     */
    public static String reportedValueOf(Analysis analysis) {
        if (analysis == null || GenericValidator.isBlankOrNull(analysis.getId())) {
            return "";
        }
        try {
            return reportedValue(SpringContext.getBean(ResultService.class).getResultsByAnalysis(analysis));
        } catch (RuntimeException e) {
            LogEvent.logError("No previous value for the correction note", e);
            return "";
        }
    }

    /**
     * The value a report showed for these results, with units: what a correction
     * replaces. Empty when nothing had been entered.
     */
    public static String reportedValue(List<Result> results) {
        if (results == null || results.isEmpty()) {
            return "";
        }
        try {
            ResultService resultService = SpringContext.getBean(ResultService.class);
            for (Result result : results) {
                if (result != null && !GenericValidator.isBlankOrNull(result.getValue())) {
                    String value = resultService.getResultValue(result, ", ", true, true);
                    return value == null ? "" : value.trim();
                }
            }
        } catch (RuntimeException e) {
            LogEvent.logError("No previous value for the correction note", e);
        }
        return "";
    }

    /** "Previously reported: {value}; corrected by {name}". */
    public static String previouslyReported(String previousValue, String correctedBy) {
        String template = PREVIOUS_DEFAULT;
        try {
            String message = MessageUtil.getMessageIfPresent(PREVIOUS_KEY);
            if (!GenericValidator.isBlankOrNull(message)) {
                template = message.trim();
            }
        } catch (RuntimeException e) {
            // no message source (e.g. outside the application context)
        }
        return MessageFormat.format(template, previousValue, correctedBy);
    }

    /**
     * Whether a result was ever corrected after its report went out: it carries the
     * "Result corrected" external note, which, unlike the analysis's
     * corrected-since-report flag, is never cleared by printing.
     */
    public static boolean hasCorrectionNote(List<Note> notes, String correctedText) {
        if (notes == null || GenericValidator.isBlankOrNull(correctedText)) {
            return false;
        }
        String wanted = correctedText.trim().toLowerCase();
        return notes.stream().anyMatch(note -> note != null && Note.EXTERNAL.equals(note.getNoteType())
                && note.getText() != null && note.getText().trim().toLowerCase().equals(wanted));
    }

    /** The "Result corrected" text in the server's language. */
    public static String correctedText() {
        try {
            return MessageUtil.getMessage(CORRECTED_KEY);
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Full name of the user making the correction, or their login. */
    public static String userName(String sysUserId) {
        try {
            return displayName(SpringContext.getBean(SystemUserService.class).getUserById(sysUserId), sysUserId);
        } catch (RuntimeException e) {
            return sysUserId == null ? "" : sysUserId;
        }
    }

    static String displayName(SystemUser user, String fallback) {
        if (user == null) {
            return fallback == null ? "" : fallback;
        }
        String name = ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
                + (user.getLastName() == null ? "" : user.getLastName())).trim();
        return GenericValidator.isBlankOrNull(name) ? user.getLoginName() : name;
    }
}
