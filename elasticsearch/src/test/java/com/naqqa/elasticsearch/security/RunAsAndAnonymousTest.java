package com.naqqa.elasticsearch.security;

import com.naqqa.elasticsearch.security.authc.AnonymousService;
import com.naqqa.elasticsearch.security.authc.RunAsService;
import com.naqqa.elasticsearch.security.authc.User;
import com.naqqa.elasticsearch.security.authz.RoleDescriptor;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class RunAsAndAnonymousTest {

    @Test
    public void runAsPermittedWhenPatternMatches() {
        RoleDescriptor role = new RoleDescriptor("service_account", Set.of(), List.of(), List.of("app_*"));
        assertTrue(RunAsService.isPermitted(List.of(role), "app_worker"));
        assertFalse(RunAsService.isPermitted(List.of(role), "other_user"));
    }

    @Test
    public void anonymousAccessResolvesWhenEnabled() {
        AnonymousService enabled = new AnonymousService(true, "_anonymous", List.of("viewer"));
        Optional<User> user = enabled.resolve();
        assertTrue(user.isPresent());
        assertEquals("_anonymous", user.get().username());

        AnonymousService disabled = AnonymousService.disabled();
        assertFalse(disabled.resolve().isPresent());
    }
}
