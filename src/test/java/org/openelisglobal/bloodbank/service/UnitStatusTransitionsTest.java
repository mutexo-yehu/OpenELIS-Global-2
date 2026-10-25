package org.openelisglobal.bloodbank.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.UnitStatus;

public class UnitStatusTransitionsTest {

    // rows = from, columns = to, in UnitStatus order:
    // AVAILABLE, QUARANTINED, RESERVED, ISSUED, TRANSFUSED, DISCARDED,
    // RETURNED_TO_SUPPLIER
    private static final String[] TABLE = { //
            "-YY--YY", // AVAILABLE
            "Y----YY", // QUARANTINED
            "Y--Y-YY", // RESERVED
            "Y---YY-", // ISSUED
            "-------", // TRANSFUSED
            "-------", // DISCARDED
            "-------" // RETURNED_TO_SUPPLIER
    };

    @Test
    public void everyMoveFollowsTheStateMachine() {
        UnitStatus[] statuses = UnitStatus.values();
        assertEquals(7, statuses.length);
        for (int from = 0; from < statuses.length; from++) {
            for (int to = 0; to < statuses.length; to++) {
                assertEquals(statuses[from] + " -> " + statuses[to], TABLE[from].charAt(to) == 'Y',
                        UnitStatusTransitions.isAllowed(statuses[from], statuses[to]));
            }
        }
    }

    @Test
    public void nullIsNeverAllowed() {
        assertFalse(UnitStatusTransitions.isAllowed(null, UnitStatus.AVAILABLE));
        assertFalse(UnitStatusTransitions.isAllowed(UnitStatus.AVAILABLE, null));
    }

    @Test
    public void finalStatesAreFinal() {
        for (UnitStatus status : UnitStatus.values()) {
            boolean anyExit = false;
            for (UnitStatus to : UnitStatus.values()) {
                anyExit |= UnitStatusTransitions.isAllowed(status, to);
            }
            assertEquals(status.name(), !status.isFinal(), anyExit);
        }
    }
}
