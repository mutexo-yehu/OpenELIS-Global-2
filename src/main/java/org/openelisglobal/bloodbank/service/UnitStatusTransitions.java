package org.openelisglobal.bloodbank.service;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.UnitStatus;

/**
 * The moves a unit may make.
 *
 * <ul>
 * <li>Receipt makes it AVAILABLE.</li>
 * <li>AVAILABLE and QUARANTINED go both ways; so do AVAILABLE and
 * RESERVED.</li>
 * <li>RESERVED goes to ISSUED. The emergency issue alone takes an AVAILABLE
 * unit straight to ISSUED, through its own checks
 * ({@link BloodIssueService#emergencyIssue}); no stock action can.</li>
 * <li>ISSUED goes back to AVAILABLE (returned in time), to DISCARDED (returned
 * late) or to TRANSFUSED.</li>
 * <li>Any state that is not issued or final may go to DISCARDED or
 * RETURNED_TO_SUPPLIER.</li>
 * <li>TRANSFUSED, DISCARDED and RETURNED_TO_SUPPLIER are final.</li>
 * </ul>
 */
public final class UnitStatusTransitions {

    private static final Map<UnitStatus, Set<UnitStatus>> ALLOWED = Map.of(UnitStatus.AVAILABLE,
            EnumSet.of(UnitStatus.QUARANTINED, UnitStatus.RESERVED, UnitStatus.DISCARDED,
                    UnitStatus.RETURNED_TO_SUPPLIER),
            UnitStatus.QUARANTINED,
            EnumSet.of(UnitStatus.AVAILABLE, UnitStatus.DISCARDED, UnitStatus.RETURNED_TO_SUPPLIER),
            UnitStatus.RESERVED,
            EnumSet.of(UnitStatus.AVAILABLE, UnitStatus.ISSUED, UnitStatus.DISCARDED, UnitStatus.RETURNED_TO_SUPPLIER),
            UnitStatus.ISSUED, EnumSet.of(UnitStatus.AVAILABLE, UnitStatus.DISCARDED, UnitStatus.TRANSFUSED),
            UnitStatus.TRANSFUSED, EnumSet.noneOf(UnitStatus.class), UnitStatus.DISCARDED,
            EnumSet.noneOf(UnitStatus.class), UnitStatus.RETURNED_TO_SUPPLIER, EnumSet.noneOf(UnitStatus.class));

    private UnitStatusTransitions() {
    }

    public static boolean isAllowed(UnitStatus from, UnitStatus to) {
        return from != null && to != null && ALLOWED.get(from).contains(to);
    }
}
