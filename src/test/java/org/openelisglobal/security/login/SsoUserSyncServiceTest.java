package org.openelisglobal.security.login;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.openelisglobal.login.service.LoginUserService;
import org.openelisglobal.login.valueholder.LoginUser;
import org.openelisglobal.role.service.RoleService;
import org.openelisglobal.role.valueholder.Role;
import org.openelisglobal.systemuser.service.SystemUserService;
import org.openelisglobal.systemuser.service.UserService;
import org.openelisglobal.systemuser.valueholder.SystemUser;
import org.openelisglobal.test.service.TestSectionService;
import org.openelisglobal.test.valueholder.TestSection;
import org.openelisglobal.userrole.service.UserRoleService;
import org.openelisglobal.userrole.valueholder.LabUnitRoleMap;
import org.openelisglobal.userrole.valueholder.UserLabUnitRoles;
import org.openelisglobal.userrole.valueholder.UserRole;
import org.springframework.test.util.ReflectionTestUtils;

@RunWith(MockitoJUnitRunner.Silent.class)
public class SsoUserSyncServiceTest {

    @Mock
    private SystemUserService systemUserService;
    @Mock
    private LoginUserService loginUserService;
    @Mock
    private RoleService roleService;
    @Mock
    private TestSectionService testSectionService;
    @Mock
    private UserRoleService userRoleService;
    @Mock
    private UserService userService;
    @InjectMocks
    private SsoUserSyncService sync;

    private SystemUser user;

    @Before
    public void setUp() {
        ReflectionTestUtils.setField(sync, "daemonSysUserId", "1");
        user = new SystemUser();
        user.setId("42");
        user.setLoginName("path1");
        user.setFirstName("Priya");
        user.setLastName("Maharaj");
        user.setInitials("PM");
        when(systemUserService.getMatch("loginName", "path1")).thenReturn(Optional.of(user));
        when(loginUserService.getMatch("loginName", "path1")).thenReturn(Optional.empty());
        role("72", "Pathologist");
        role("5", "Results");
        role("7", "Reports");
        TestSection pathology = new TestSection();
        pathology.setId("163");
        when(testSectionService.getTestSectionByName("Pathology")).thenReturn(pathology);
    }

    private void role(String id, String name) {
        Role role = new Role();
        role.setId(id);
        role.setName(name);
        when(roleService.getRoleByName(name)).thenReturn(role);
    }

    private void saved(List<String> roleIds, Map<String, Set<String>> labUnits) {
        when(userRoleService.getRoleIdsForUser("42")).thenReturn(new ArrayList<>(roleIds));
        UserLabUnitRoles lab = new UserLabUnitRoles();
        Set<LabUnitRoleMap> maps = new java.util.HashSet<>();
        labUnits.forEach((unit, roles) -> {
            LabUnitRoleMap map = new LabUnitRoleMap();
            map.setLabUnit(unit);
            map.setRoles(roles);
            maps.add(map);
        });
        lab.setLabUnitRoleMap(maps);
        when(userRoleService.getUserLabUnitRoles("42")).thenReturn(lab);
    }

    @Test
    public void parse_splitsGlobalAndLabUnitRoles_ignoringOtherAuthorities() {
        SsoUserSyncService.AssertedRoles roles = SsoUserSyncService.AssertedRoles
                .parse(List.of("oeg-Pathologist", "oeg-Results-Pathology", "oeg-Reports-AllLabUnits", "oeg-Audit Trail",
                        "ROLE_RESULTS", "offline_access"));
        assertEquals(Set.of("Pathologist", "Audit Trail"), roles.global());
        assertEquals(Map.of("Pathology", Set.of("Results"), "AllLabUnits", Set.of("Reports")), roles.byLabUnit());
    }

    @Test
    public void unchangedUser_writesNothing() {
        saved(List.of("72", "5"), Map.of("163", Set.of("5")));
        sync.sync("path1", "Priya", "Maharaj", List.of("oeg-Pathologist", "oeg-Results-Pathology", "ROLE_PATHOLOGIST"));
        verify(systemUserService, never()).update(any());
        verify(userService, never()).saveUserLabUnitRoles(any(), any(), anyString());
        verify(userRoleService, never()).insert(any());
        verify(userRoleService, never()).deleteAll(anyList());
    }

    @Test
    public void grantsAssertedRoles_andRemovesOnesNoLongerAsserted() {
        saved(List.of("7"), Map.of());
        sync.sync("path1", "Priya", "Maharaj", List.of("oeg-Pathologist", "oeg-Results-Pathology"));

        verify(userService).saveUserLabUnitRoles(eq(user), eq(Map.of("163", Set.of("5"))), eq("1"));
        ArgumentCaptor<UserRole> granted = ArgumentCaptor.forClass(UserRole.class);
        verify(userRoleService, org.mockito.Mockito.times(2)).insert(granted.capture());
        assertEquals(Set.of("72", "5"),
                Set.of(granted.getAllValues().get(0).getRoleId(), granted.getAllValues().get(1).getRoleId()));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserRole>> removed = ArgumentCaptor.forClass(List.class);
        verify(userRoleService).deleteAll(removed.capture());
        assertEquals("7", removed.getValue().get(0).getRoleId());
    }

    @Test
    public void unknownRoleOrLabUnit_isIgnored() {
        saved(List.of(), Map.of());
        sync.sync("path1", "Priya", "Maharaj", List.of("oeg-No Such Role", "oeg-Results-No Such Unit"));
        verify(userService, never()).saveUserLabUnitRoles(any(), any(), anyString());
        verify(userRoleService, never()).insert(any());
    }

    @Test
    public void nameFromIdentityProvider_isSavedTrimmedToTheColumns() {
        saved(List.of(), Map.of());
        String longLast = "Maharaj-Ramkissoon-Singh-Persad-Extra";
        sync.sync("path1", " Priya ", longLast, List.of());
        verify(systemUserService).update(user);
        assertEquals("Priya", user.getFirstName());
        assertEquals(longLast.substring(0, 30), user.getLastName());
        assertEquals("PM", user.getInitials());
    }

    @Test
    public void noNameSent_keepsTheSavedName() {
        saved(List.of(), Map.of());
        sync.sync("path1", null, "", List.of());
        verify(systemUserService, never()).update(any());
        assertEquals("Priya", user.getFirstName());
    }

    @Test
    public void localAccount_isLeftAlone() {
        when(loginUserService.getMatch("loginName", "path1")).thenReturn(Optional.of(new LoginUser()));
        sync.sync("path1", "Someone", "Else", List.of("oeg-Pathologist"));
        verify(systemUserService, never()).update(any());
        verify(userRoleService, never()).insert(any());
        verify(userService, never()).saveUserLabUnitRoles(any(), any(), anyString());
    }
}
