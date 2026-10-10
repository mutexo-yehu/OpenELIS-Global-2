package org.openelisglobal.reports.action.implementation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.List;
import org.junit.Test;
import org.openelisglobal.audittrail.valueholder.History;
import org.openelisglobal.systemuser.valueholder.SystemUser;

public class ReportValidatorsTest {

    private static final String AWAITING_VALIDATION = "15";

    @Test
    public void validationIsTheUpdateThatReplacedTheAwaitingValidationStatus() {
        History resultEntry = update("115", "2026-10-10 00:59:57", "<statusId>4</statusId>");
        History validation = update("114", "2026-10-10 01:01:08", "<statusId>15</statusId>");

        assertEquals("114", ReportValidators.validationEntry(List.of(resultEntry, validation), AWAITING_VALIDATION)
                .get().getSysUserId());
    }

    @Test
    public void aRevalidationCountsTheLatestOne() {
        History first = update("114", "2026-10-10 01:01:08", "<statusId>15</statusId>");
        History second = update("116", "2026-10-10 02:30:00", "<statusId>15</statusId>");

        assertEquals("116",
                ReportValidators.validationEntry(List.of(second, first), AWAITING_VALIDATION).get().getSysUserId());
    }

    @Test
    public void noValidationYet() {
        History resultEntry = update("115", "2026-10-10 00:59:57", "<statusId>4</statusId>");
        History insert = update("1", "2026-10-10 00:56:49", null);
        insert.setActivity("I");

        assertFalse(ReportValidators.validationEntry(List.of(resultEntry, insert), AWAITING_VALIDATION).isPresent());
        assertFalse(ReportValidators.validationEntry(null, AWAITING_VALIDATION).isPresent());
    }

    @Test
    public void displayNameFallsBackToTheLogin() {
        SystemUser named = new SystemUser();
        named.setFirstName("Ria");
        named.setLastName("Ramdeen");
        named.setLoginName("sci1");
        SystemUser unnamed = new SystemUser();
        unnamed.setFirstName("");
        unnamed.setLoginName("sci2");

        assertEquals("Ria Ramdeen", ReportValidators.displayName(named));
        assertEquals("sci2", ReportValidators.displayName(unnamed));
        assertEquals("", ReportValidators.displayName(null));
    }

    private static History update(String userId, String time, String replacedValues) {
        History history = new History();
        history.setActivity("U");
        history.setSysUserId(userId);
        history.setTimestamp(Timestamp.valueOf(time));
        history.setChanges(replacedValues == null ? null : replacedValues.getBytes(StandardCharsets.UTF_8));
        return history;
    }
}
