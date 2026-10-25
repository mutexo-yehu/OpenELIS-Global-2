package org.openelisglobal.bloodbank.controller.rest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import org.junit.Test;
import org.openelisglobal.bloodbank.form.BloodBankForms.IssueRequest;
import org.openelisglobal.bloodbank.form.BloodBankViews.IssueCheck;
import org.openelisglobal.bloodbank.safety.PatientBloodProfile;
import org.openelisglobal.bloodbank.safety.PatientBloodProfile.CrossmatchResult;
import org.openelisglobal.bloodbank.safety.PatientBloodProfile.PatientGroup;
import org.openelisglobal.bloodbank.safety.PatientBloodProfile.TypingResult;
import org.openelisglobal.bloodbank.safety.PatientBloodProfile.TypingResult.Kind;
import org.openelisglobal.bloodbank.safety.SafetyCheck;
import org.openelisglobal.bloodbank.service.BloodBankSettings;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.Abo;
import org.openelisglobal.bloodbank.valueholder.BloodBankEnums.RhD;

/**
 * The JSON the frontend reads, through a mapper configured as the application's
 * (JavaTimeModule, nulls left out): times as ISO strings, no helper methods
 * leaking into the payload, and request records read back.
 */
public class BloodBankJsonContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .setSerializationInclusion(Include.NON_NULL);

    @Test
    public void theProfileCarriesIsoTimesAndNoHelperFields() throws Exception {
        Instant collected = Instant.parse("2026-10-26T10:00:00Z");
        PatientBloodProfile profile = new PatientBloodProfile(501L, new PatientGroup(Abo.A, RhD.POS, true, false, 2, 2),
                List.of(new TypingResult(Kind.ABO, 1L, 10L, "LAB-1", collected, "A")), null, null,
                List.of(new CrossmatchResult(9L, 10L, "LAB-1", collected, collected.plusSeconds(72 * 3600),
                        "Compatible", true, null, null)),
                List.of("bloodbank.test.dat"));

        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(profile));

        assertEquals("A", json.at("/group/abo").asText());
        assertTrue(json.at("/group/confirmed").asBoolean());
        assertFalse("helper methods are not fields", json.get("group").has("trustedAbo"));
        assertEquals("2026-10-26T10:00:00Z", json.at("/typings/0/collectedAt").asText());
        assertEquals("2026-10-29T10:00:00Z", json.at("/crossmatches/0/validUntil").asText());
        assertFalse("nulls are left out", json.get("crossmatches").get(0).has("linkedUnitId"));
        assertFalse(json.has("dat"));
        assertEquals("bloodbank.test.dat", json.at("/notConfigured/0").asText());
    }

    @Test
    public void aChecklistLineHasCodeStatusKeyAndArgs() throws Exception {
        IssueCheck check = new IssueCheck(1L, 501L, true, true,
                List.of(SafetyCheck.warn("RHD", "bloodbank.check.rhd.positivePlateletsForNegative", "POS", "NEG")));

        JsonNode line = MAPPER.readTree(MAPPER.writeValueAsString(check)).at("/checks/0");

        assertEquals("RHD", line.get("code").asText());
        assertEquals("WARN", line.get("status").asText());
        assertEquals("bloodbank.check.rhd.positivePlateletsForNegative", line.get("messageKey").asText());
        assertEquals(2, line.get("args").size());
        assertFalse(line.has("passed"));
    }

    @Test
    public void settingsDoNotExposeTheDerivedRules() throws Exception {
        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(BloodBankSettings.DEFAULTS));

        assertEquals("BLOCK", json.get("unconfirmedGroupORedCells").asText());
        assertEquals(72, json.get("crossmatchValidHours").asInt());
        assertFalse(json.has("rules"));
    }

    @Test
    public void theIssueRequestIsReadFromJsonAndNeverPrintsThePassword() throws Exception {
        IssueRequest request = MAPPER.readValue(
                "{\"patientIdentifier\":\"LAB-1\",\"location\":\"Ward 3\","
                        + "\"collectorName\":\"Porter\",\"secondCheckerLogin\":\"checker\","
                        + "\"secondCheckerPassword\":\"s3cret-value\",\"warningReason\":\"agreed\"}",
                IssueRequest.class);

        assertEquals("checker", request.secondCheckerLogin());
        assertEquals("s3cret-value", request.secondCheckerPassword());
        assertFalse(request.toString().contains("s3cret-value"));
    }
}
