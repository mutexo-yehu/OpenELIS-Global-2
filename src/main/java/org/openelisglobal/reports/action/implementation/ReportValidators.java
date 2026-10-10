package org.openelisglobal.reports.action.implementation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.validator.GenericValidator;
import org.openelisglobal.analysis.valueholder.Analysis;
import org.openelisglobal.audittrail.valueholder.History;
import org.openelisglobal.common.log.LogEvent;
import org.openelisglobal.common.services.IStatusService;
import org.openelisglobal.common.services.StatusService.AnalysisStatus;
import org.openelisglobal.common.util.DateUtil;
import org.openelisglobal.history.service.HistoryService;
import org.openelisglobal.internationalization.MessageUtil;
import org.openelisglobal.referencetables.service.ReferenceTablesService;
import org.openelisglobal.spring.util.SpringContext;
import org.openelisglobal.systemuser.service.SystemUserService;
import org.openelisglobal.systemuser.valueholder.SystemUser;

/**
 * Who validated the results on a patient report, for the "Validated by" line.
 *
 * <p>
 * Validation leaves no user on the analysis itself; the audit history does. An
 * analysis update records the values it replaced, so the update that moved an
 * analysis out of Technical Acceptance (awaiting validation) is its validation,
 * and that history row's user is the validator.
 *
 * <p>
 * A result finalized at entry by the auto-validation rules has no such update
 * and no release date (every validator release stamps one), so the report says
 * it was auto-validated instead of leaving the line blank.
 */
public final class ReportValidators {

    static final String AUTO_VALIDATED_KEY = "report.autoValidated";
    static final String AUTO_VALIDATED_DEFAULT = "Auto-validated by rules";

    private ReportValidators() {
    }

    /**
     * "Name (dd/MM/yyyy HH:mm)" for each person who validated the finalized
     * analyses, in validation order, then "Auto-validated by rules" when any were
     * finalized by the rules; several are joined with "; ". Empty when none can be
     * found.
     */
    public static String describe(List<Analysis> analyses) {
        try {
            IStatusService statusService = SpringContext.getBean(IStatusService.class);
            String finalized = statusService.getStatusID(AnalysisStatus.Finalized);
            String awaitingValidation = statusService.getStatusID(AnalysisStatus.TechnicalAcceptance);
            String analysisTable = SpringContext.getBean(ReferenceTablesService.class)
                    .getReferenceTableByName("ANALYSIS").getId();
            HistoryService historyService = SpringContext.getBean(HistoryService.class);

            List<History> validations = new ArrayList<>();
            boolean anyAutoValidated = false;
            for (Analysis analysis : analyses) {
                if (finalized.equals(analysis.getStatusId())) {
                    Optional<History> validation = validationEntry(
                            historyService.getHistoryByRefIdAndRefTableId(analysis.getId(), analysisTable),
                            awaitingValidation);
                    validation.ifPresent(validations::add);
                    anyAutoValidated |= isAutoValidated(analysis, validation.isPresent());
                }
            }
            validations.sort(Comparator.comparing(History::getTimestamp));

            // one entry per person: their latest validation time on this order
            Map<String, History> byUser = new LinkedHashMap<>();
            for (History validation : validations) {
                byUser.put(validation.getSysUserId(), validation);
            }
            SystemUserService userService = SpringContext.getBean(SystemUserService.class);
            List<String> lines = new ArrayList<>();
            for (History validation : byUser.values()) {
                lines.add(displayName(userService.get(validation.getSysUserId())) + " ("
                        + DateUtil.convertTimestampToStringDateAndConfiguredHourTime(validation.getTimestamp()) + ")");
            }
            if (anyAutoValidated) {
                lines.add(autoValidatedLabel());
            }
            return String.join("; ", lines);
        } catch (RuntimeException e) {
            LogEvent.logError("No 'validated by' line for the patient report: the report prints without it", e);
            return "";
        }
    }

    /**
     * The history row of an analysis's validation: its latest update made while the
     * analysis was awaiting validation (the replaced values carry that status).
     */
    static Optional<History> validationEntry(List<History> history, String awaitingValidationStatusId) {
        if (history == null) {
            return Optional.empty();
        }
        String replacedStatus = "<statusId>" + awaitingValidationStatusId + "</statusId>";
        return history.stream().filter(h -> "U".equals(h.getActivity()) && h.getChanges() != null)
                .filter(h -> new String(h.getChanges(), StandardCharsets.UTF_8).contains(replacedStatus))
                .max(Comparator.comparing(History::getTimestamp));
    }

    /**
     * A finalized analysis no validator released: no release date (validator
     * releases stamp one) and no validation update in its history.
     */
    static boolean isAutoValidated(Analysis finalizedAnalysis, boolean hasValidationEntry) {
        return finalizedAnalysis.getReleasedDate() == null && !hasValidationEntry;
    }

    static String autoValidatedLabel() {
        try {
            String message = MessageUtil.getMessageIfPresent(AUTO_VALIDATED_KEY);
            return GenericValidator.isBlankOrNull(message) ? AUTO_VALIDATED_DEFAULT : message.trim();
        } catch (RuntimeException e) {
            // no message source (e.g. outside the application context)
            return AUTO_VALIDATED_DEFAULT;
        }
    }

    static String displayName(SystemUser user) {
        if (user == null) {
            return "";
        }
        String name = ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
                + (user.getLastName() == null ? "" : user.getLastName())).trim();
        return GenericValidator.isBlankOrNull(name) ? user.getLoginName() : name;
    }
}
