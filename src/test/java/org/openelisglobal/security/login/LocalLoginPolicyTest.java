package org.openelisglobal.security.login;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

public class LocalLoginPolicyTest {

    private static UserDetails user(String name, boolean enabled, boolean nonLocked) {
        return new User(name, "hash", enabled, true, true, nonLocked, List.of(new SimpleGrantedAuthority("ROLE_X")));
    }

    @Test
    public void emptySetting_allowsEveryone() {
        LocalLoginPolicy policy = new LocalLoginPolicy("");
        assertFalse(policy.isRestricted());
        assertTrue(policy.isAllowed("anyone"));
        policy.preAuthenticationChecks().check(user("anyone", true, true));
    }

    @Test
    public void allowList_isTrimmedAndCaseInsensitive() {
        LocalLoginPolicy policy = new LocalLoginPolicy(" admin , Bridge-Service ,");
        assertTrue(policy.isRestricted());
        assertTrue(policy.isAllowed("ADMIN"));
        assertTrue(policy.isAllowed("bridge-service"));
        assertFalse(policy.isAllowed("sci1"));
        assertFalse(policy.isAllowed(null));
    }

    @Test
    public void allowedAccount_passesChecks() {
        new LocalLoginPolicy("admin").preAuthenticationChecks().check(user("admin", true, true));
    }

    @Test(expected = DisabledException.class)
    public void accountNotOnList_isRefused() {
        new LocalLoginPolicy("admin").preAuthenticationChecks().check(user("sci1", true, true));
    }

    @Test(expected = LockedException.class)
    public void lockedAccount_isStillRefused() {
        new LocalLoginPolicy("admin").preAuthenticationChecks().check(user("admin", true, false));
    }

    @Test(expected = DisabledException.class)
    public void disabledAccount_isStillRefused() {
        new LocalLoginPolicy("").preAuthenticationChecks().check(user("admin", false, true));
    }
}
