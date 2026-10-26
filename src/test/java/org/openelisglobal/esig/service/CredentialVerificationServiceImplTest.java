package org.openelisglobal.esig.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.openelisglobal.esig.valueholder.AuthMethod;
import org.openelisglobal.login.service.LoginUserService;
import org.openelisglobal.login.valueholder.LoginUser;
import org.openelisglobal.security.login.LocalLoginPolicy;
import org.openelisglobal.systemuser.service.SystemUserService;
import org.springframework.test.util.ReflectionTestUtils;

@RunWith(MockitoJUnitRunner.Silent.class)
public class CredentialVerificationServiceImplTest {

    @Mock
    private LoginUserService loginUserService;
    @Mock
    private SystemUserService systemUserService;
    @Mock
    private KeycloakCredentialVerifier keycloakCredentialVerifier;
    @InjectMocks
    private CredentialVerificationServiceImpl service;

    @Before
    public void setUp() {
        sso(true);
        // as on an SSO site: only the break-glass admin may use a local password
        ReflectionTestUtils.setField(service, "localLoginPolicy", new LocalLoginPolicy("admin,analyzer-bridge"));
    }

    private void sso(boolean on) {
        ReflectionTestUtils.setField(service, "samlEnabled", on);
        ReflectionTestUtils.setField(service, "oauthEnabled", false);
    }

    private void localPasswordIsValid(String loginName, String password) {
        LoginUser user = new LoginUser();
        user.setAccountDisabled("N");
        user.setAccountLocked("N");
        when(loginUserService.getValidatedLogin(loginName, password)).thenReturn(Optional.of(user));
    }

    @Test
    public void aSingleSignOnUserIsCheckedByKeycloakWithTheirCode() {
        AuthMethod method = service.verifyCredentialsByLoginName("sci1", "secret", "123456");

        assertEquals(AuthMethod.KEYCLOAK, method);
        verify(keycloakCredentialVerifier).verify("sci1", "secret", "123456");
        verifyZeroInteractions(loginUserService);
    }

    @Test
    public void otherModulesReachTheCodeCheckThroughTheOneTimeCodeInterface() {
        // a module that needs the code check (a second-person check) finds this bean by
        // that interface
        OneTimeCodeCredentialVerifier codes = service;

        assertEquals(AuthMethod.KEYCLOAK, codes.verifyCredentialsByLoginName("sci1", "secret", "123456"));
        verify(keycloakCredentialVerifier).verify("sci1", "secret", "123456");
    }

    @Test
    public void aLocalPasswordCannotStandInForSingleSignOn() {
        // even if sci1 still had a local password, signing goes through Keycloak (MFA)
        localPasswordIsValid("sci1", "old-local");

        service.verifyCredentialsByLoginName("sci1", "old-local", null);

        verify(keycloakCredentialVerifier).verify("sci1", "old-local", null);
        verify(loginUserService, never()).getValidatedLogin("sci1", "old-local");
    }

    @Test
    public void theBreakGlassAdministratorSignsWithTheLocalPassword() {
        localPasswordIsValid("admin", "local-pw");

        AuthMethod method = service.verifyCredentialsByLoginName("admin", "local-pw", null);

        assertEquals(AuthMethod.LOCAL, method);
        verifyZeroInteractions(keycloakCredentialVerifier);
    }

    @Test
    public void keycloakRefusingTheCredentialsRefusesTheSignature() {
        doThrow(new IllegalArgumentException("Invalid credentials")).when(keycloakCredentialVerifier).verify("sci1",
                "wrong", "000000");

        assertThrows(IllegalArgumentException.class,
                () -> service.verifyCredentialsByLoginName("sci1", "wrong", "000000"));
    }

    @Test
    public void withoutSingleSignOnEveryoneSignsLocally() {
        sso(false);
        localPasswordIsValid("tech1", "local-pw");

        assertEquals(AuthMethod.LOCAL, service.verifyCredentialsByLoginName("tech1", "local-pw"));
        verifyZeroInteractions(keycloakCredentialVerifier);
    }

    @Test
    public void onlySingleSignOnUsersAreAskedForACode() {
        assertTrue(service.signsWithKeycloak("sci1"));
        assertFalse(service.signsWithKeycloak("admin"));
        sso(false);
        assertFalse(service.signsWithKeycloak("sci1"));
    }

    @Test
    public void anSsoSiteWithoutAnAllowListSendsEveryoneToKeycloak() {
        ReflectionTestUtils.setField(service, "localLoginPolicy", new LocalLoginPolicy(""));

        assertTrue(service.signsWithKeycloak("admin"));
    }
}
