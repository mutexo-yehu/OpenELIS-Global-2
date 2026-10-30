package org.openelisglobal.analyzer.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.openelisglobal.analyzer.service.AnalyzerOrderDispatchService;
import org.openelisglobal.analyzer.service.AnalyzerOrderDispatchService.EnqueueOutcome;
import org.openelisglobal.analyzer.service.AnalyzerOrderDispatchService.EnqueueResult;
import org.openelisglobal.analyzer.service.AnalyzerOrderDispatchView;
import org.openelisglobal.analyzer.service.AnalyzerWorklistSettings;
import org.openelisglobal.common.action.IActionConstants;
import org.openelisglobal.login.valueholder.UserSessionData;
import org.openelisglobal.security.SecuritySliceMockMvcTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@WebAppConfiguration
@ContextConfiguration(classes = { AnalyzerWorklistRestControllerSecurityTest.TestConfig.class })
@TestPropertySource("classpath:common.properties")
public class AnalyzerWorklistRestControllerSecurityTest extends SecuritySliceMockMvcTest {

    private static final String BASE = "/rest/analyzer/worklist";
    private static final String LAB_NO = "DEV01260000000000130";

    @Autowired
    private AnalyzerOrderDispatchService dispatchService;

    @Autowired
    private AnalyzerWorklistSettings settings;

    @Before
    public void resetMocks() {
        reset(dispatchService, settings);
        when(settings.isEnabled()).thenReturn(true);
        when(dispatchService.recent(200)).thenReturn(List.of(new AnalyzerOrderDispatchView("9", LAB_NO, "5",
                "GeneXpert (mock) - Molecular", "COVID-19 PCR", List.of("94500-6"), "FAILED", 2,
                "HTTP 502: ASTM send failed", "2026-10-30T12:00:00Z", null, "2026-10-30T12:00:30Z")));
    }

    @Test
    public void listWithoutAuthentication_returns401() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    }

    @Test
    public void listWithResultsRole_returns403() throws Exception {
        mockMvc.perform(get(BASE).with(user("results").roles("RESULTS"))).andExpect(status().isForbidden());
        verify(dispatchService, never()).recent(200);
    }

    @Test
    public void listWithAnalyserImportRole_returnsTheRows() throws Exception {
        mockMvc.perform(get(BASE).with(user("operator").roles("ANALYSER_IMPORT"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true)).andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.rows[0].accessionNumber").value(LAB_NO))
                .andExpect(jsonPath("$.data.rows[0].analyzerName").value("GeneXpert (mock) - Molecular"))
                .andExpect(jsonPath("$.data.rows[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data.rows[0].lastError").value("HTTP 502: ASTM send failed"));
    }

    @Test
    public void retryWithResultsRole_returns403() throws Exception {
        mockMvc.perform(post(BASE + "/9/retry").with(user("results").roles("RESULTS")))
                .andExpect(status().isForbidden());
        verifyZeroInteractions(dispatchService);
    }

    @Test
    public void retryWithAdminRole_setsTheRowBackToPending() throws Exception {
        when(dispatchService.retry("9", "17")).thenReturn(true);

        mockMvc.perform(post(BASE + "/9/retry").with(user("admin").roles("ADMIN"))
                .requestAttr(IActionConstants.USER_SESSION_DATA, sessionFor(17))).andExpect(status().isOk());
        verify(dispatchService).retry("9", "17");
    }

    @Test
    public void retryOfARowThatIsNotFailed_returns409() throws Exception {
        when(dispatchService.retry("9", "17")).thenReturn(false);

        mockMvc.perform(post(BASE + "/9/retry").with(user("admin").roles("ADMIN"))
                .requestAttr(IActionConstants.USER_SESSION_DATA, sessionFor(17))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.messageKey").value("analyzer.worklist.error.notRetryable"));
    }

    @Test
    public void sendWithResultsRole_returns403() throws Exception {
        mockMvc.perform(post(BASE + "/send").param("accessionNumber", LAB_NO).with(user("results").roles("RESULTS")))
                .andExpect(status().isForbidden());
        verifyZeroInteractions(dispatchService);
    }

    @Test
    public void sendWithAnalyserImportRole_queuesTheOrder() throws Exception {
        when(dispatchService.enqueueAccession(LAB_NO, "17")).thenReturn(new EnqueueResult(EnqueueOutcome.QUEUED, 1));

        mockMvc.perform(post(BASE + "/send").param("accessionNumber", LAB_NO)
                .with(user("operator").roles("ANALYSER_IMPORT"))
                .requestAttr(IActionConstants.USER_SESSION_DATA, sessionFor(17))).andExpect(status().isOk())
                .andExpect(jsonPath("$.queued").value(1));
    }

    @Test
    public void sendOfAnOrderWithNothingForAnAnalyser_returns422() throws Exception {
        when(dispatchService.enqueueAccession(LAB_NO, "17"))
                .thenReturn(new EnqueueResult(EnqueueOutcome.NOTHING_TO_SEND, 0));

        mockMvc.perform(post(BASE + "/send").param("accessionNumber", LAB_NO).with(user("admin").roles("ADMIN"))
                .requestAttr(IActionConstants.USER_SESSION_DATA, sessionFor(17)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.messageKey").value("analyzer.worklist.error.nothingToSend"));
    }

    @Test
    public void sendOfAnUnknownLabNumber_returns404() throws Exception {
        when(dispatchService.enqueueAccession("NOPE", "17")).thenReturn(new EnqueueResult(EnqueueOutcome.NOT_FOUND, 0));

        mockMvc.perform(post(BASE + "/send").param("accessionNumber", "NOPE").with(user("admin").roles("ADMIN"))
                .requestAttr(IActionConstants.USER_SESSION_DATA, sessionFor(17))).andExpect(status().isNotFound());
    }

    private static UserSessionData sessionFor(int systemUserId) {
        UserSessionData session = new UserSessionData();
        session.setSytemUserId(systemUserId);
        return session;
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @EnableMethodSecurity(prePostEnabled = true)
    static class TestConfig {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated()).httpBasic(Customizer.withDefaults())
                    .csrf(csrf -> csrf.disable());
            return http.build();
        }

        @Bean
        AnalyzerOrderDispatchService dispatchService() {
            return mock(AnalyzerOrderDispatchService.class);
        }

        @Bean
        AnalyzerWorklistSettings worklistSettings() {
            return mock(AnalyzerWorklistSettings.class);
        }

        @Bean
        AnalyzerWorklistRestController analyzerWorklistRestController(AnalyzerOrderDispatchService dispatchService,
                AnalyzerWorklistSettings worklistSettings) {
            return new AnalyzerWorklistRestController(dispatchService, worklistSettings);
        }
    }
}
