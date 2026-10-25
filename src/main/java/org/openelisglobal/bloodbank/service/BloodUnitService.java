package org.openelisglobal.bloodbank.service;

import java.util.List;
import org.openelisglobal.bloodbank.form.BloodBankForms.ReceiveLine;
import org.openelisglobal.bloodbank.form.BloodBankViews.ParsedLabel;
import org.openelisglobal.bloodbank.form.BloodBankViews.ReceiveLineResult;
import org.openelisglobal.bloodbank.form.BloodBankViews.StockSummary;
import org.openelisglobal.bloodbank.form.BloodBankViews.UnitActionResult;
import org.openelisglobal.bloodbank.form.BloodBankViews.UnitDetail;
import org.openelisglobal.bloodbank.form.BloodBankViews.UnitView;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.Abo;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.RhD;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.UnitStatus;

/** Receipt and stock: what is on the shelf and what happens to it there. */
public interface BloodUnitService {

    /** Reads label scans; writes nothing. */
    ParsedLabel parse(List<String> scans);

    /**
     * Receives a delivery. Each line is checked on its own (duplicate DIN + product
     * code, expired, unknown product code, unreadable group, missing field) and
     * gets its own result; the good lines are received.
     */
    List<ReceiveLineResult> receive(List<ReceiveLine> lines, String sysUserId);

    /**
     * @param expiringWithinHours only units expiring within this many hours
     *                            (expired ones included)
     */
    List<UnitView> search(List<UnitStatus> statuses, Long productTypeId, Abo abo, RhD rhd, Integer expiringWithinHours,
            Long patientId);

    StockSummary summary();

    UnitDetail get(Long unitId);

    /** AVAILABLE → QUARANTINED. A reason is required. */
    UnitActionResult quarantine(Long unitId, String reason, String sysUserId);

    /** QUARANTINED → AVAILABLE. */
    UnitActionResult release(Long unitId, String reason, String sysUserId);

    /**
     * Any state that is not issued or final → DISCARDED. A reservation still live
     * is released first, with its own trail entry. A reason is required.
     */
    UnitActionResult discard(Long unitId, String reason, String sysUserId);

    /** As {@link #discard}, to RETURNED_TO_SUPPLIER. */
    UnitActionResult returnToSupplier(Long unitId, String reason, String sysUserId);
}
