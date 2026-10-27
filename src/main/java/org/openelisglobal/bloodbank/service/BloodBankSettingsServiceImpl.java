package org.openelisglobal.bloodbank.service;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.openelisglobal.bloodbank.safety.BloodBankRules.RuleAction;
import org.openelisglobal.bloodbank.safety.SecondCheckMode;
import org.openelisglobal.bloodbank.service.BloodBankException.Kind;
import org.openelisglobal.siteinformation.service.SiteInformationService;
import org.openelisglobal.siteinformation.valueholder.SiteInformation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class BloodBankSettingsServiceImpl implements BloodBankSettingsService {

    /** JSON key → site_information name. */
    private static final Map<String, String> KEYS = new LinkedHashMap<>();

    static {
        KEYS.put("testAbo", BloodBankSettings.TEST_ABO);
        KEYS.put("testRhd", BloodBankSettings.TEST_RHD);
        KEYS.put("testAntibodyScreen", BloodBankSettings.TEST_ANTIBODY_SCREEN);
        KEYS.put("testDat", BloodBankSettings.TEST_DAT);
        KEYS.put("testCrossmatch", BloodBankSettings.TEST_CROSSMATCH);
        KEYS.put("crossmatchCompatibleValues", BloodBankSettings.CROSSMATCH_COMPATIBLE_VALUES);
        KEYS.put("crossmatchValidHours", BloodBankSettings.CROSSMATCH_VALID_HOURS);
        KEYS.put("expiryWarnHours", BloodBankSettings.EXPIRY_WARN_HOURS);
        KEYS.put("returnWindowMinutes", BloodBankSettings.RETURN_WINDOW_MINUTES);
        KEYS.put("twoSampleGroup", BloodBankSettings.RULE_TWO_SAMPLE);
        KEYS.put("rhdNegPatientRhdPosPlatelets", BloodBankSettings.RULE_RHD_PLATELETS);
        KEYS.put("unconfirmedGroupORedCells", BloodBankSettings.RULE_UNCONFIRMED_O);
        KEYS.put("secondCheck", BloodBankSettings.SECOND_CHECK);
    }

    @Autowired
    private SiteInformationService siteInformationService;

    @Override
    @Transactional(readOnly = true)
    public BloodBankSettings get() {
        BloodBankSettings d = BloodBankSettings.DEFAULTS;
        String compatible = text(BloodBankSettings.CROSSMATCH_COMPATIBLE_VALUES, "Compatible");
        return new BloodBankSettings(text(BloodBankSettings.TEST_ABO, ""), text(BloodBankSettings.TEST_RHD, ""),
                text(BloodBankSettings.TEST_ANTIBODY_SCREEN, ""), text(BloodBankSettings.TEST_DAT, ""),
                text(BloodBankSettings.TEST_CROSSMATCH, ""), splitValues(compatible),
                number(BloodBankSettings.CROSSMATCH_VALID_HOURS, d.crossmatchValidHours(), 1, 720),
                number(BloodBankSettings.EXPIRY_WARN_HOURS, d.expiryWarnHours(), 1, 720),
                number(BloodBankSettings.RETURN_WINDOW_MINUTES, d.returnWindowMinutes(), 0, 1440),
                !"false".equalsIgnoreCase(text(BloodBankSettings.RULE_TWO_SAMPLE, "true")),
                RuleAction.parse(text(BloodBankSettings.RULE_RHD_PLATELETS, null), d.rhdNegPatientRhdPosPlatelets()),
                RuleAction.parse(text(BloodBankSettings.RULE_UNCONFIRMED_O, null), d.unconfirmedGroupORedCells()),
                SecondCheckMode.parse(text(BloodBankSettings.SECOND_CHECK, null)));
    }

    @Override
    public BloodBankSettings update(Map<String, Object> changes, String sysUserId) {
        if (changes == null || changes.isEmpty()) {
            throw new BloodBankException(Kind.INVALID, "bloodbank.settings.error.empty");
        }
        Map<String, String> validated = new LinkedHashMap<>();
        for (Map.Entry<String, Object> change : changes.entrySet()) {
            String name = KEYS.get(change.getKey());
            if (name == null) {
                throw new BloodBankException(Kind.INVALID, "bloodbank.settings.error.unknown", change.getKey());
            }
            validated.put(name, validate(change.getKey(), change.getValue()));
        }
        for (Map.Entry<String, String> entry : validated.entrySet()) {
            SiteInformation setting = siteInformationService.getSiteInformationByName(entry.getKey());
            if (setting == null) {
                throw new BloodBankException(Kind.NOT_FOUND, "bloodbank.settings.error.missingRow", entry.getKey());
            }
            setting.setValue(entry.getValue());
            setting.setSysUserId(sysUserId);
            siteInformationService.update(setting);
        }
        return get();
    }

    private static String validate(String key, Object value) {
        if (value == null) {
            throw new BloodBankException(Kind.INVALID, "bloodbank.settings.error.value", key);
        }
        switch (key) {
        case "crossmatchCompatibleValues":
            List<String> values = value instanceof Collection<?> list ? list.stream().map(String::valueOf).toList()
                    : splitValues(String.valueOf(value));
            if (values.stream().allMatch(String::isBlank)) {
                throw new BloodBankException(Kind.INVALID, "bloodbank.settings.error.value", key);
            }
            return String.join(",", values.stream().map(String::trim).filter(v -> !v.isEmpty()).toList());
        case "crossmatchValidHours":
        case "expiryWarnHours":
            return String.valueOf(wholeNumber(key, value, 1, 720));
        case "returnWindowMinutes":
            return String.valueOf(wholeNumber(key, value, 0, 1440));
        case "twoSampleGroup":
            String flag = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
            if (!"true".equals(flag) && !"false".equals(flag)) {
                throw new BloodBankException(Kind.INVALID, "bloodbank.settings.error.value", key);
            }
            return flag;
        case "rhdNegPatientRhdPosPlatelets":
        case "unconfirmedGroupORedCells":
            RuleAction action = RuleAction.parse(String.valueOf(value), null);
            if (action == null) {
                throw new BloodBankException(Kind.INVALID, "bloodbank.settings.error.value", key);
            }
            return action.name();
        case "secondCheck":
            SecondCheckMode mode = SecondCheckMode.strictParse(String.valueOf(value));
            if (mode == null) {
                throw new BloodBankException(Kind.INVALID, "bloodbank.settings.error.value", key);
            }
            return mode.name();
        default:
            // test catalogue codes: free text, may be cleared
            String code = String.valueOf(value).trim();
            if (code.length() > 100) {
                throw new BloodBankException(Kind.INVALID, "bloodbank.settings.error.value", key);
            }
            return code;
        }
    }

    private static int wholeNumber(String key, Object value, int min, int max) {
        try {
            int number = Integer.parseInt(String.valueOf(value).trim());
            if (number < min || number > max) {
                throw new BloodBankException(Kind.INVALID, "bloodbank.settings.error.range", key, String.valueOf(min),
                        String.valueOf(max));
            }
            return number;
        } catch (NumberFormatException e) {
            throw new BloodBankException(Kind.INVALID, "bloodbank.settings.error.value", key);
        }
    }

    private String text(String name, String fallback) {
        SiteInformation setting = siteInformationService.getSiteInformationByName(name);
        if (setting == null || setting.getValue() == null) {
            return fallback;
        }
        return setting.getValue().trim();
    }

    private int number(String name, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(text(name, String.valueOf(fallback)));
            return value < min || value > max ? fallback : value;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static List<String> splitValues(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList();
    }
}
