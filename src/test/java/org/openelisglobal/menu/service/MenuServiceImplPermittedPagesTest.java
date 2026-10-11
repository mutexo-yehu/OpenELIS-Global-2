package org.openelisglobal.menu.service;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.openelisglobal.common.action.IActionConstants;
import org.openelisglobal.userrole.service.UserRoleService;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** The menu shows the pages the sign-in permitted, as the page guard does. */
@RunWith(MockitoJUnitRunner.class)
public class MenuServiceImplPermittedPagesTest {

    @Mock
    private UserRoleService userRoleService;

    @InjectMocks
    private MenuServiceImpl menuService;

    private MockHttpServletRequest request;

    @Before
    public void setUp() {
        request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @After
    public void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    public void singleSignOnUserGetsTheSessionPermissions() {
        // SSO stores the identity provider's permissions in the session; no roles are
        // saved
        request.getSession().setAttribute(IActionConstants.PERMITTED_ACTIONS_MAP, Set.of("PatientManagement"));

        assertEquals(Set.of("PatientManagement"), menuService.permittedPages("115"));
        verify(userRoleService, never()).getAllPermittedPagesForUser(anyString());
    }

    @Test
    public void basicAuthRequestGetsTheRequestPermissions() {
        request.setAttribute(IActionConstants.PERMITTED_ACTIONS_MAP, Set.of("Workplan"));

        assertEquals(Set.of("Workplan"), menuService.permittedPages("1"));
    }

    @Test
    public void withoutSignInPermissionsTheSavedRolesDecide() {
        when(userRoleService.getAllPermittedPagesForUser("7")).thenReturn(Set.of("Results"));

        assertEquals(Set.of("Results"), menuService.permittedPages("7"));
    }

    @Test
    public void outsideARequestTheSavedRolesDecide() {
        RequestContextHolder.resetRequestAttributes();
        when(userRoleService.getAllPermittedPagesForUser("7")).thenReturn(Set.of("Results"));

        assertEquals(Set.of("Results"), menuService.permittedPages("7"));
    }
}
