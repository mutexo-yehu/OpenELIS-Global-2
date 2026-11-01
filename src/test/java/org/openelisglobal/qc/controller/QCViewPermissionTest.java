package org.openelisglobal.qc.controller;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.openelisglobal.common.constants.Constants;
import org.openelisglobal.qa.security.QaPermissions;
import org.openelisglobal.security.login.CustomUserDetailsService;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.access.expression.SecurityExpressionRoot;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * A QA Officer (qa.view.qc) reads the QC pages and changes nothing; the Global
 * Administrator does both. The {@code @InitBinder} method runs before every
 * handler of its controller, so it must admit whoever may read: a class rule of
 * hasRole('ADMIN') on it refused the QA Officer on every QC page.
 */
public class QCViewPermissionTest {

    private static final List<Class<?>> QUALITY_CONTROL = List.of(QCRestController.class, QCAlertRestController.class,
            QCChartDataRestController.class, QCViolationRestController.class);

    private static final Authentication QA_OFFICER = login("QA Officer", QaPermissions.KEY_VIEW_QC);
    private static final Authentication GLOBAL_ADMIN = login(Constants.ROLE_GLOBAL_ADMIN);

    @Test
    public void qaOfficerReadsEveryQcPageIncludingTheBinderEachRequestRuns() {
        for (Class<?> controller : QUALITY_CONTROL) {
            for (Method method : controller.getDeclaredMethods()) {
                if (isRead(method) || AnnotatedElementUtils.hasAnnotation(method, InitBinder.class)) {
                    assertTrue(name(method) + " refuses the QA Officer", admits(controller, method, QA_OFFICER));
                }
            }
        }
    }

    @Test
    public void onlyGlobalAdminChangesQc() {
        for (Class<?> controller : QUALITY_CONTROL) {
            for (Method method : controller.getDeclaredMethods()) {
                if (isHandler(method) && !isRead(method)) {
                    assertFalse(name(method) + " lets the QA Officer change QC",
                            admits(controller, method, QA_OFFICER));
                    assertTrue(name(method) + " refuses Global Admin", admits(controller, method, GLOBAL_ADMIN));
                }
            }
        }
    }

    private static Authentication login(String roleName, String... permissions) {
        Set<String> authorities = new LinkedHashSet<>();
        CustomUserDetailsService.addAuthoritiesForRole(roleName, authorities);
        authorities.addAll(List.of(permissions));
        return UsernamePasswordAuthenticationToken.authenticated("user", null,
                authorities.stream().map(SimpleGrantedAuthority::new).toList());
    }

    private static boolean admits(Class<?> controller, Method method, Authentication user) {
        PreAuthorize rule = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
        if (rule == null) {
            rule = AnnotatedElementUtils.findMergedAnnotation(controller, PreAuthorize.class);
        }
        if (rule == null) {
            return true;
        }
        SecurityExpressionRoot root = new SecurityExpressionRoot(user) {
        };
        return Boolean.TRUE.equals(new SpelExpressionParser().parseExpression(rule.value())
                .getValue(new StandardEvaluationContext(root), Boolean.class));
    }

    private static boolean isHandler(Method method) {
        return AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class);
    }

    private static boolean isRead(Method method) {
        return AnnotatedElementUtils.hasAnnotation(method, GetMapping.class);
    }

    private static String name(Method method) {
        return method.getDeclaringClass().getSimpleName() + "#" + method.getName();
    }
}
