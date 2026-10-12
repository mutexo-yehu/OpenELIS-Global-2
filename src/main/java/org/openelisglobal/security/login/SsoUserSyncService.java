package org.openelisglobal.security.login;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.apache.commons.lang3.StringUtils;
import org.openelisglobal.common.log.LogEvent;
import org.openelisglobal.login.service.LoginUserService;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes the saved OpenELIS user match what the identity provider asserts at
 * single sign-on: first and last name, global roles and lab-unit roles.
 *
 * <p>
 * Without it, SSO roles live only in the session. Everything that reads
 * <em>saved</em> roles then misses SSO users: role pick-lists
 * ({@code /rest/users/{role}}, e.g. the pathologist on a case), and reports
 * print the login instead of a name. Saving what the session already holds
 * grants nothing new.
 *
 * <p>
 * Users with a local password account (the break-glass admin, service accounts)
 * are managed in OpenELIS and left alone. Nothing is written when the saved
 * user already matches. Every role change goes through {@link UserRoleService},
 * whose audit trail records it.
 */
@Service
public class SsoUserSyncService {

    static final String OEG_PREFIX = "oeg-";
    static final String ALL_LAB_UNITS = "AllLabUnits";
    private static final int NAME_LENGTH = 30;
    private static final int INITIALS_LENGTH = 3;

    @Autowired
    private SystemUserService systemUserService;
    @Autowired
    private LoginUserService loginUserService;
    @Autowired
    private RoleService roleService;
    @Autowired
    private TestSectionService testSectionService;
    @Autowired
    private UserRoleService userRoleService;
    @Autowired
    private UserService userService;

    @Autowired
    @Qualifier("daemonSysUserId")
    private String daemonSysUserId;

    /**
     * The roles in the assertion, by name: global roles ({@code oeg-Pathologist})
     * and roles per lab unit ({@code oeg-Results-Pathology},
     * {@code oeg-Reports-AllLabUnits}). Split on the first dash, as
     * KeycloakAuthoritiesExtractor does.
     */
    record AssertedRoles(Set<String> global, Map<String, Set<String>> byLabUnit) {

        static AssertedRoles parse(Collection<String> authorities) {
            Set<String> global = new LinkedHashSet<>();
            Map<String, Set<String>> byLabUnit = new LinkedHashMap<>();
            for (String authority : authorities) {
                if (authority == null || !authority.startsWith(OEG_PREFIX)) {
                    continue;
                }
                String stripped = authority.substring(OEG_PREFIX.length()).trim();
                int dash = stripped.indexOf('-');
                String role = (dash >= 0 ? stripped.substring(0, dash) : stripped).trim();
                if (role.isEmpty()) {
                    continue;
                }
                String labUnit = dash >= 0 ? stripped.substring(dash + 1).trim() : "";
                if (labUnit.isEmpty()) {
                    global.add(role);
                } else {
                    byLabUnit.computeIfAbsent(labUnit, k -> new LinkedHashSet<>()).add(role);
                }
            }
            return new AssertedRoles(global, byLabUnit);
        }
    }

    /**
     * @param authorities the authority names of the SSO sign-in; only the
     *                    {@code oeg-*} ones are read
     */
    @Transactional
    public void sync(String loginName, String firstName, String lastName, Collection<String> authorities) {
        Optional<SystemUser> found = systemUserService.getMatch("loginName", loginName);
        if (found.isEmpty()) {
            return;
        }
        if (loginUserService.getMatch("loginName", loginName).isPresent()) {
            // a local account: its roles are managed in OpenELIS, not by the identity
            // provider
            return;
        }
        SystemUser user = found.get();
        syncName(user, firstName, lastName);
        syncRoles(user, AssertedRoles.parse(authorities));
    }

    private void syncName(SystemUser user, String firstName, String lastName) {
        String first = fit(firstName, NAME_LENGTH);
        String last = fit(lastName, NAME_LENGTH);
        if (first.isEmpty() && last.isEmpty()) {
            return; // the identity provider sent no name; keep what OpenELIS has
        }
        String initials = fit(initial(first) + initial(last), INITIALS_LENGTH);
        if (first.equals(user.getFirstName()) && last.equals(user.getLastName())
                && initials.equals(user.getInitials())) {
            return;
        }
        user.setFirstName(first);
        user.setLastName(last);
        user.setInitials(initials);
        user.setSysUserId(daemonSysUserId);
        systemUserService.update(user);
        LogEvent.logInfo(getClass().getSimpleName(), "syncName",
                "SSO sync: name of " + user.getLoginName() + " set from the identity provider");
    }

    private void syncRoles(SystemUser user, AssertedRoles asserted) {
        Set<String> wantedGlobal = new HashSet<>();
        for (String name : asserted.global()) {
            roleId(name).ifPresent(wantedGlobal::add);
        }
        Map<String, Set<String>> wantedLab = new TreeMap<>();
        for (Map.Entry<String, Set<String>> entry : asserted.byLabUnit().entrySet()) {
            Optional<String> unit = labUnitKey(entry.getKey());
            if (unit.isEmpty()) {
                continue;
            }
            for (String name : entry.getValue()) {
                roleId(name).ifPresent(id -> wantedLab.computeIfAbsent(unit.get(), k -> new HashSet<>()).add(id));
            }
        }

        if (!wantedLab.equals(savedLabUnitRoles(user.getId()))) {
            // replaces the lab-unit map; it also adds the lab roles to system_user_role,
            // which the step below then trims to exactly what was asserted
            userService.saveUserLabUnitRoles(user, wantedLab, daemonSysUserId);
            LogEvent.logInfo(getClass().getSimpleName(), "syncRoles",
                    "SSO sync: lab-unit roles of " + user.getLoginName() + " set to " + wantedLab);
        }

        // system_user_role holds the global roles plus every lab-unit role, as the user
        // administration page saves them
        Set<String> wanted = new HashSet<>(wantedGlobal);
        wantedLab.values().forEach(wanted::addAll);
        Set<String> saved = new HashSet<>(userRoleService.getRoleIdsForUser(user.getId()));
        for (String roleId : wanted) {
            if (!saved.contains(roleId)) {
                userRoleService.insert(userRole(user, roleId));
                LogEvent.logInfo(getClass().getSimpleName(), "syncRoles",
                        "SSO sync: granted role " + roleId + " to " + user.getLoginName());
            }
        }
        for (String roleId : saved) {
            if (!wanted.contains(roleId)) {
                userRoleService.deleteAll(List.of(userRole(user, roleId)));
                LogEvent.logInfo(getClass().getSimpleName(), "syncRoles",
                        "SSO sync: removed role " + roleId + " from " + user.getLoginName());
            }
        }
    }

    private Map<String, Set<String>> savedLabUnitRoles(String systemUserId) {
        Map<String, Set<String>> saved = new TreeMap<>();
        UserLabUnitRoles roles = userRoleService.getUserLabUnitRoles(systemUserId);
        if (roles != null && roles.getLabUnitRoleMap() != null) {
            for (LabUnitRoleMap map : roles.getLabUnitRoleMap()) {
                if (map.getRoles() != null && !map.getRoles().isEmpty()) {
                    saved.computeIfAbsent(map.getLabUnit(), k -> new HashSet<>()).addAll(map.getRoles());
                }
            }
        }
        return saved;
    }

    private Optional<String> roleId(String name) {
        Role role = roleService.getRoleByName(name);
        if (role == null) {
            LogEvent.logWarn(getClass().getSimpleName(), "roleId",
                    "SSO sync: no OpenELIS role named '" + name + "'; ignored");
            return Optional.empty();
        }
        return Optional.of(role.getId());
    }

    private Optional<String> labUnitKey(String name) {
        if (ALL_LAB_UNITS.equalsIgnoreCase(name)) {
            return Optional.of(ALL_LAB_UNITS);
        }
        TestSection section = testSectionService.getTestSectionByName(name);
        if (section == null) {
            LogEvent.logWarn(getClass().getSimpleName(), "labUnitKey",
                    "SSO sync: no lab unit named '" + name + "'; ignored");
            return Optional.empty();
        }
        return Optional.of(section.getId());
    }

    private UserRole userRole(SystemUser user, String roleId) {
        UserRole userRole = new UserRole();
        userRole.setSystemUserId(user.getId());
        userRole.setRoleId(roleId);
        userRole.setSysUserId(daemonSysUserId);
        return userRole;
    }

    static String fit(String value, int length) {
        String trimmed = Objects.toString(value, "").trim();
        return StringUtils.left(trimmed, length);
    }

    private static String initial(String name) {
        return name.isEmpty() ? "" : name.substring(0, 1);
    }

    /** The authority names of a sign-in, for {@link #sync}. */
    public static Set<String> authorityNames(
            Collection<? extends org.springframework.security.core.GrantedAuthority> authorities) {
        Set<String> names = new LinkedHashSet<>();
        authorities.forEach(a -> names.add(a.getAuthority()));
        return names;
    }

}
