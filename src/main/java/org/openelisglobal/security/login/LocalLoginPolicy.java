package org.openelisglobal.security.login;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.openelisglobal.common.log.LogEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AccountExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsChecker;
import org.springframework.stereotype.Component;

/**
 * Decides which accounts may sign in with an OpenELIS (local) password, through
 * the login form or HTTP Basic.
 *
 * When single sign-on is used, staff should only ever sign in through the
 * identity provider, where multi-factor authentication is enforced. Setting
 * {@code org.itech.login.local.allowedUsers} to a comma-separated list (for
 * example a break-glass administrator and the Analyzer Bridge's service
 * account) makes every other account fail local sign-in, even if it still has a
 * local password. When the property is empty (the default), every account may
 * sign in locally, exactly as before.
 */
@Component
public class LocalLoginPolicy {

    private final Set<String> allowedUsers;

    public LocalLoginPolicy(@Value("${org.itech.login.local.allowedUsers:}") String allowedUsers) {
        this.allowedUsers = parse(allowedUsers);
    }

    static Set<String> parse(String allowedUsers) {
        if (allowedUsers == null || allowedUsers.isBlank()) {
            return Collections.emptySet();
        }
        return Arrays.stream(allowedUsers.split(",")).map(String::trim).filter(name -> !name.isEmpty())
                .map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    /** True when local sign-in is limited to the configured accounts. */
    public boolean isRestricted() {
        return !allowedUsers.isEmpty();
    }

    public boolean isAllowed(String loginName) {
        if (!isRestricted()) {
            return true;
        }
        return loginName != null && allowedUsers.contains(loginName.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Pre-authentication checks for the local (password) authentication provider:
     * the allow-list first, then Spring Security's usual account-status checks,
     * which a custom checker replaces.
     */
    public UserDetailsChecker preAuthenticationChecks() {
        return new UserDetailsChecker() {
            @Override
            public void check(UserDetails user) {
                if (!isAllowed(user.getUsername())) {
                    LogEvent.logWarn(LocalLoginPolicy.class.getSimpleName(), "check", "Local sign-in refused for "
                            + user.getUsername() + ": not in org.itech.login.local.allowedUsers (use single sign-on)");
                    throw new DisabledException("Local sign-in is not allowed for this account");
                }
                if (!user.isAccountNonLocked()) {
                    throw new LockedException("User account is locked");
                }
                if (!user.isEnabled()) {
                    throw new DisabledException("User is disabled");
                }
                if (!user.isAccountNonExpired()) {
                    throw new AccountExpiredException("User account has expired");
                }
            }
        };
    }
}
