package org.openelisglobal.qc.controller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.openelisglobal.common.action.IActionConstants;
import org.openelisglobal.common.constants.Constants;
import org.openelisglobal.login.valueholder.UserSessionData;
import org.openelisglobal.qa.security.QaPermissions;
import org.openelisglobal.qc.service.QCControlLotService;
import org.openelisglobal.qc.service.QCDashboardService;
import org.openelisglobal.qc.service.QCStatisticsService;
import org.openelisglobal.qc.service.WestgardRuleConfigService;
import org.openelisglobal.qc.valueholder.QCControlLot;
import org.openelisglobal.qc.valueholder.QCStatistics;
import org.openelisglobal.security.SecuritySliceMockMvcTest;
import org.openelisglobal.security.login.CustomUserDetailsService;
import org.openelisglobal.testsupport.SliceSecurityConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.access.expression.SecurityExpressionRoot;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Who may call {@link QCRestController}: a user holding {@code qa.view.qc}
 * reads control lots, statistics, rule configuration and the QC dashboard; only
 * the Global Administrator changes any of it.
 *
 * <p>
 * The {@code @InitBinder} method is invoked before every handler that binds a
 * request parameter, path variable or body, so it must admit whoever may read.
 * While it inherited the class rule {@code hasRole('ADMIN')}, a
 * {@code qa.view.qc} user was refused even by {@code /dashboard/bench}, the one
 * handler that already named {@link QaPermissions#VIEW_QC}.
 */
@WebAppConfiguration
@ContextConfiguration(classes = { SliceSecurityConfig.class, QCRestControllerSecurityTest.TestConfig.class })
@TestPropertySource("classpath:common.properties")
public class QCRestControllerSecurityTest extends SecuritySliceMockMvcTest {

    private static final String LOT = "lot-1";

    /** Every GET handler on the controller, with the parameters it requires. */
    private static final List<String> READS = List.of("/rest/qc/controlLots?instrumentId=7", "/rest/qc/control-lots",
            "/rest/qc/controlLot/" + LOT, "/rest/qc/controlLot/byLotNumber/LOT-2026-001",
            "/rest/qc/controlLot/" + LOT + "/statistics", "/rest/qc/ruleConfig?testId=42&instrumentId=7",
            "/rest/qc/ruleConfig/enabled?testId=42&instrumentId=7", "/rest/qc/ruleConfig/summaries",
            "/rest/qc/ruleConfig/unconfigured", "/rest/qc/dashboard/bench?months=1", "/rest/qc/dashboard/summary",
            "/rest/qc/dashboard/instruments", "/rest/qc/dashboard/instruments/7");

    private static final String CONTROL_LOT_BODY = "{\"productName\":\"Lyphochek Chemistry\","
            + "\"lotNumber\":\"LOT-2026-001\",\"controlLevel\":\"LEVEL_1\",\"testId\":\"42\",\"instrumentId\":\"7\","
            + "\"activationDate\":1767225600000}";

    private static final String RULE_CONFIG_BODY = "{\"ruleCode\":\"R_1_3S\",\"enabled\":false}";

    private static final RequestPostProcessor QC_VIEWER = user("qc-viewer")
            .authorities(new SimpleGrantedAuthority(QaPermissions.KEY_VIEW_QC));

    // The authorities a Global Administrator's login carries for the role
    // itself; the qa.* permission keys are left out so the administrator
    // fallback is what admits them.
    private static final RequestPostProcessor GLOBAL_ADMIN = user("admin")
            .authorities(roleAuthorities(Constants.ROLE_GLOBAL_ADMIN));

    private static final RequestPostProcessor RESULTS = user("bench-tech").roles("RESULTS");

    // #4657 took QC administration away from the analyser import role.
    private static final RequestPostProcessor ANALYSER_IMPORT = user("importer").roles("ANALYSER_IMPORT");

    private QCControlLotService controlLotService;
    private QCStatisticsService statisticsService;
    private WestgardRuleConfigService ruleConfigService;
    private QCDashboardService dashboardService;

    @Before
    public void resetServices() {
        controlLotService = webApplicationContext.getBean(QCControlLotService.class);
        statisticsService = webApplicationContext.getBean(QCStatisticsService.class);
        ruleConfigService = webApplicationContext.getBean(WestgardRuleConfigService.class);
        dashboardService = webApplicationContext.getBean(QCDashboardService.class);
        reset(controlLotService, statisticsService, ruleConfigService, dashboardService);
        when(controlLotService.get(LOT)).thenReturn(new QCControlLot());
        when(controlLotService.getControlLotByLotNumber("LOT-2026-001")).thenReturn(new QCControlLot());
        when(statisticsService.getLatestStatistics(LOT)).thenReturn(new QCStatistics());
    }

    @Test
    public void qcViewerReadsEveryQcPage() throws Exception {
        assertEquals("reads not answered 200 for qa.view.qc", List.of(), notAnswered(200, reads(), QC_VIEWER));
    }

    @Test
    public void globalAdminStillReadsEveryQcPage() throws Exception {
        assertEquals("reads not answered 200 for Global Administrator", List.of(),
                notAnswered(200, reads(), GLOBAL_ADMIN));
    }

    @Test
    public void userWithoutQcViewNorAdminReadsNothing() throws Exception {
        assertEquals("reads not refused for Results", List.of(), notAnswered(403, reads(), RESULTS));
        assertEquals("reads not refused for Analyser Import", List.of(), notAnswered(403, reads(), ANALYSER_IMPORT));
        verifyZeroInteractions(controlLotService, statisticsService, ruleConfigService, dashboardService);
    }

    @Test
    public void qcViewerChangesNothing() throws Exception {
        assertEquals("changes not refused for qa.view.qc", List.of(), notAnswered(403, writes(), QC_VIEWER));
        verifyZeroInteractions(controlLotService, statisticsService, ruleConfigService, dashboardService);
    }

    @Test
    public void userWithoutQcViewNorAdminChangesNothing() throws Exception {
        assertEquals("changes not refused for Results", List.of(), notAnswered(403, writes(), RESULTS));
        assertEquals("changes not refused for Analyser Import", List.of(), notAnswered(403, writes(), ANALYSER_IMPORT));
        verifyZeroInteractions(controlLotService, statisticsService, ruleConfigService, dashboardService);
    }

    @Test
    public void globalAdminStillChangesQc() throws Exception {
        List<String> refused = new ArrayList<>();
        for (MockHttpServletRequestBuilder write : writes()) {
            String label = describe(write);
            int status = statusOf(
                    write.with(GLOBAL_ADMIN).sessionAttr(IActionConstants.USER_SESSION_DATA, sessionUser()));
            if (status == 401 || status == 403) {
                refused.add(label + " -> " + status);
            }
        }
        assertEquals("changes refused for Global Administrator", List.of(), refused);
        // each handler ran and reached its service
        ArgumentCaptor<QCControlLot> created = ArgumentCaptor.forClass(QCControlLot.class);
        verify(controlLotService).createControlLot(created.capture());
        assertEquals("LOT-2026-001", created.getValue().getLotNumber());
        verify(controlLotService).activateControlLot(LOT);
        verify(controlLotService).deactivateControlLot(LOT);
        verify(statisticsService).calculateInitialRunsStatistics(LOT, 20);
        verify(statisticsService).calculateRollingStatistics(LOT, 30);
        verify(ruleConfigService).get("rule-1");
        verify(ruleConfigService).applyPreset("42", "7", "STANDARD");
        verify(ruleConfigService).createDefaultConfig("42", "7");
        verify(ruleConfigService).validateRuleConfig(List.of());
    }

    /**
     * Every handler, including any added later: a GET (and the binder each one
     * runs) admits {@code qa.view.qc}; anything else keeps the class rule and
     * admits only the administrator.
     */
    @Test
    public void everyHandlerKeepsChangesWithTheAdministrator() {
        Authentication qcViewer = login(Set.of(QaPermissions.KEY_VIEW_QC));
        Authentication globalAdmin = login(roleNames(Constants.ROLE_GLOBAL_ADMIN));
        Authentication results = login(Set.of("ROLE_RESULTS"));
        int reads = 0;
        int changes = 0;
        for (Method method : QCRestController.class.getDeclaredMethods()) {
            boolean binder = AnnotatedElementUtils.hasAnnotation(method, InitBinder.class);
            boolean handler = AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class);
            if (!binder && !handler) {
                continue;
            }
            String name = "QCRestController#" + method.getName();
            boolean read = binder || AnnotatedElementUtils.hasAnnotation(method, GetMapping.class);
            assertEquals(name + (read ? " refuses" : " admits") + " qa.view.qc", read, admits(method, qcViewer));
            assertTrue(name + " refuses the Global Administrator", admits(method, globalAdmin));
            assertFalse(name + " admits Results", admits(method, results));
            if (handler) {
                if (read) {
                    reads++;
                } else {
                    changes++;
                }
            }
        }
        // keeps the request lists above covering every handler
        assertEquals("GET handlers not listed in READS", READS.size(), reads);
        assertEquals("changing handlers not listed in writes()", writes().size(), changes);
        // a handler that names no rule of its own falls back to administrator only
        assertFalse("the class rule admits qa.view.qc", evaluate(
                AnnotatedElementUtils.findMergedAnnotation(QCRestController.class, PreAuthorize.class), qcViewer));
    }

    private List<MockHttpServletRequestBuilder> writes() {
        return List.of(post("/rest/qc/controlLot").contentType(MediaType.APPLICATION_JSON).content(CONTROL_LOT_BODY),
                put("/rest/qc/controlLot/" + LOT + "/activate"), put("/rest/qc/controlLot/" + LOT + "/deactivate"),
                post("/rest/qc/controlLot/" + LOT + "/statistics/initialRuns?requiredRuns=20"),
                post("/rest/qc/controlLot/" + LOT + "/statistics/rolling?windowSize=30"),
                put("/rest/qc/ruleConfig/rule-1").contentType(MediaType.APPLICATION_JSON).content(RULE_CONFIG_BODY),
                post("/rest/qc/ruleConfig/preset?testId=42&instrumentId=7&preset=STANDARD"),
                post("/rest/qc/ruleConfig/defaults?testId=42&instrumentId=7"),
                post("/rest/qc/ruleConfig/validate?testId=42&instrumentId=7"));
    }

    private List<MockHttpServletRequestBuilder> reads() {
        return READS.stream().map(url -> (MockHttpServletRequestBuilder) get(url)).toList();
    }

    /** Each request sent as {@code user} that was not answered {@code expected}. */
    private List<String> notAnswered(int expected, List<MockHttpServletRequestBuilder> requests,
            RequestPostProcessor user) throws Exception {
        List<String> mismatches = new ArrayList<>();
        for (MockHttpServletRequestBuilder request : requests) {
            String label = describe(request);
            int status = statusOf(request.with(user).sessionAttr(IActionConstants.USER_SESSION_DATA, sessionUser()));
            if (status != expected) {
                mismatches.add(label + " -> " + status);
            }
        }
        return mismatches;
    }

    private int statusOf(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private static String describe(MockHttpServletRequestBuilder request) {
        MockHttpServletRequest built = request.buildRequest(new MockServletContext());
        String query = built.getQueryString() == null ? "" : "?" + built.getQueryString();
        return built.getMethod() + " " + built.getRequestURI() + query;
    }

    private static UserSessionData sessionUser() {
        UserSessionData userSessionData = new UserSessionData();
        userSessionData.setSytemUserId(1);
        return userSessionData;
    }

    private static Set<String> roleNames(String roleName) {
        Set<String> authorities = new LinkedHashSet<>();
        CustomUserDetailsService.addAuthoritiesForRole(roleName, authorities);
        return authorities;
    }

    private static List<GrantedAuthority> roleAuthorities(String roleName) {
        return roleNames(roleName).stream().map(name -> (GrantedAuthority) new SimpleGrantedAuthority(name)).toList();
    }

    private static Authentication login(Set<String> authorities) {
        return UsernamePasswordAuthenticationToken.authenticated("user", null,
                authorities.stream().map(SimpleGrantedAuthority::new).toList());
    }

    private static boolean admits(Method method, Authentication user) {
        PreAuthorize rule = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
        if (rule == null) {
            rule = AnnotatedElementUtils.findMergedAnnotation(QCRestController.class, PreAuthorize.class);
        }
        return evaluate(rule, user);
    }

    private static boolean evaluate(PreAuthorize rule, Authentication user) {
        if (rule == null) {
            return true;
        }
        SecurityExpressionRoot root = new SecurityExpressionRoot(user) {
        };
        return Boolean.TRUE.equals(new SpelExpressionParser().parseExpression(rule.value())
                .getValue(new StandardEvaluationContext(root), Boolean.class));
    }

    @Configuration
    static class TestConfig {

        @Bean
        QCControlLotService controlLotService() {
            return mock(QCControlLotService.class);
        }

        @Bean
        QCStatisticsService statisticsService() {
            return mock(QCStatisticsService.class);
        }

        @Bean
        WestgardRuleConfigService ruleConfigService() {
            return mock(WestgardRuleConfigService.class);
        }

        @Bean
        QCDashboardService dashboardService() {
            return mock(QCDashboardService.class);
        }

        @Bean
        QCRestController qcRestController(QCControlLotService controlLotService, QCStatisticsService statisticsService,
                WestgardRuleConfigService ruleConfigService, QCDashboardService dashboardService) {
            QCRestController controller = new QCRestController();
            ReflectionTestUtils.setField(controller, "controlLotService", controlLotService);
            ReflectionTestUtils.setField(controller, "statisticsService", statisticsService);
            ReflectionTestUtils.setField(controller, "ruleConfigService", ruleConfigService);
            ReflectionTestUtils.setField(controller, "dashboardService", dashboardService);
            return controller;
        }
    }
}
