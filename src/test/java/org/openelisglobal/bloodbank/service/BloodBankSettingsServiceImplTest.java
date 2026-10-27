package org.openelisglobal.bloodbank.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.openelisglobal.bloodbank.safety.BloodBankRules.RuleAction;
import org.openelisglobal.bloodbank.safety.SecondCheckMode;
import org.openelisglobal.bloodbank.service.BloodBankException.Kind;
import org.openelisglobal.siteinformation.service.SiteInformationService;
import org.openelisglobal.siteinformation.valueholder.SiteInformation;

@RunWith(MockitoJUnitRunner.class)
public class BloodBankSettingsServiceImplTest {

    @Mock
    private SiteInformationService siteInformationService;

    @InjectMocks
    private BloodBankSettingsServiceImpl service;

    private SiteInformation row(String name, String value) {
        SiteInformation row = new SiteInformation();
        row.setName(name);
        row.setValue(value);
        when(siteInformationService.getSiteInformationByName(name)).thenReturn(row);
        return row;
    }

    @Test
    public void missingRowsFallBackToTheConservativeDefaults() {
        BloodBankSettings settings = service.get();

        assertEquals(BloodBankSettings.DEFAULTS, settings);
        assertTrue(settings.twoSampleGroup());
        assertEquals(RuleAction.WARN, settings.rhdNegPatientRhdPosPlatelets());
        assertEquals(RuleAction.BLOCK, settings.unconfirmedGroupORedCells());
        assertEquals(SecondCheckMode.REQUIRED, settings.secondCheck());
    }

    @Test
    public void storedValuesAreRead() {
        row(BloodBankSettings.TEST_ABO, " SW-ABO ");
        row(BloodBankSettings.CROSSMATCH_COMPATIBLE_VALUES, "Compatible, Compatible (IAT) ,");
        row(BloodBankSettings.CROSSMATCH_VALID_HOURS, "96");
        row(BloodBankSettings.RULE_TWO_SAMPLE, "false");
        row(BloodBankSettings.RULE_UNCONFIRMED_O, "allow");

        BloodBankSettings settings = service.get();

        assertEquals("SW-ABO", settings.testAbo());
        assertEquals(List.of("Compatible", "Compatible (IAT)"), settings.crossmatchCompatibleValues());
        assertEquals(96, settings.crossmatchValidHours());
        assertEquals(false, settings.twoSampleGroup());
        assertEquals(RuleAction.ALLOW, settings.unconfirmedGroupORedCells());
    }

    @Test
    public void unreadableStoredValuesFallBack() {
        row(BloodBankSettings.CROSSMATCH_VALID_HOURS, "lots");
        row(BloodBankSettings.RETURN_WINDOW_MINUTES, "-5");
        row(BloodBankSettings.RULE_RHD_PLATELETS, "MAYBE");

        BloodBankSettings settings = service.get();

        assertEquals(72, settings.crossmatchValidHours());
        assertEquals(30, settings.returnWindowMinutes());
        assertEquals(RuleAction.WARN, settings.rhdNegPatientRhdPosPlatelets());
    }

    @Test
    public void updateSavesValidatedValues() {
        SiteInformation hours = row(BloodBankSettings.CROSSMATCH_VALID_HOURS, "72");
        SiteInformation rule = row(BloodBankSettings.RULE_RHD_PLATELETS, "WARN");
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("crossmatchValidHours", 48);
        changes.put("rhdNegPatientRhdPosPlatelets", "block");

        service.update(changes, "1");

        verify(siteInformationService)
                .update(argThat(s -> s == hours && "48".equals(s.getValue()) && "1".equals(s.getSysUserId())));
        verify(siteInformationService).update(argThat(s -> s == rule && "BLOCK".equals(s.getValue())));
    }

    @Test
    public void oneBadValueSavesNothing() {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("crossmatchValidHours", 48);
        changes.put("returnWindowMinutes", 5000);

        try {
            service.update(changes, "1");
            fail("expected a refusal");
        } catch (BloodBankException e) {
            assertEquals(Kind.INVALID, e.getKind());
            assertEquals("bloodbank.settings.error.range", e.getErrorCode());
            assertEquals(List.of("returnWindowMinutes", "0", "1440"), e.getParams());
        }
        verify(siteInformationService, never()).update(ArgumentCaptor.forClass(SiteInformation.class).capture());
    }

    @Test
    public void unknownKeysAndBadRuleValuesAreRefused() {
        assertEquals("bloodbank.settings.error.unknown", refusal(Map.of("colour", "red")));
        assertEquals("bloodbank.settings.error.value", refusal(Map.of("unconfirmedGroupORedCells", "SOMETIMES")));
        assertEquals("bloodbank.settings.error.value", refusal(Map.of("twoSampleGroup", "yes")));
        assertEquals("bloodbank.settings.error.value", refusal(Map.of("crossmatchCompatibleValues", " , ")));
        assertEquals("bloodbank.settings.error.range", refusal(Map.of("crossmatchValidHours", 0)));
        assertEquals("bloodbank.settings.error.empty", refusal(Map.of()));
        assertEquals("bloodbank.settings.error.value", refusal(Map.of("secondCheck", "SOMETIMES")));
    }

    private String refusal(Map<String, Object> changes) {
        try {
            service.update(changes, "1");
        } catch (BloodBankException e) {
            return e.getErrorCode();
        }
        fail("expected a refusal");
        return null;
    }

    @Test
    public void theSecondCheckSettingIsReadAndSaved() {
        SiteInformation mode = row(BloodBankSettings.SECOND_CHECK, "optional_with_reason");
        assertEquals(SecondCheckMode.OPTIONAL_WITH_REASON, service.get().secondCheck());

        mode.setValue("whatever");
        assertEquals(SecondCheckMode.REQUIRED, service.get().secondCheck());

        service.update(Map.of("secondCheck", "collector_confirms"), "1");
        verify(siteInformationService).update(argThat(s -> s == mode && "COLLECTOR_CONFIRMS".equals(s.getValue())));
    }

    @Test
    public void emergencyIssueIsOnByDefaultAndOffOnlyWhenSetToFalse() {
        assertTrue("missing row", service.get().emergencyIssue());
        SiteInformation flag = row(BloodBankSettings.EMERGENCY_ISSUE, "FALSE");
        assertEquals(false, service.get().emergencyIssue());
        flag.setValue("rubbish");
        assertTrue(service.get().emergencyIssue());

        service.update(Map.of("emergencyIssue", "True"), "1");
        verify(siteInformationService).update(argThat(s -> s == flag && "true".equals(s.getValue())));
        assertEquals("bloodbank.settings.error.value", refusal(Map.of("emergencyIssue", "sometimes")));
    }
}
