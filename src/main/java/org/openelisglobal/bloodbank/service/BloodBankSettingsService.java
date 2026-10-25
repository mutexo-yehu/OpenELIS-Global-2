package org.openelisglobal.bloodbank.service;

import java.util.Map;

public interface BloodBankSettingsService {

    BloodBankSettings get();

    /**
     * Changes the settings named in {@code changes} (keys as in
     * {@link BloodBankSettings}' JSON: {@code testAbo},
     * {@code crossmatchValidHours}, ...); others stay as they are. Every value is
     * validated before any is saved.
     *
     * @throws BloodBankException INVALID for an unknown key or a bad value
     */
    BloodBankSettings update(Map<String, Object> changes, String sysUserId);
}
