package com.naqqa.auth.service.authorities;

import com.naqqa.auth.config.authorities.AuthoritiesErrors;
import com.naqqa.auth.entity.authorities.AuthorityEntity;
import com.naqqa.auth.exceptions.ForbiddenException;
import com.naqqa.auth.sso.admin.SsoAdminAuthorities;
import com.naqqa.auth.sso.admin.SsoAdminScope;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public final class RbacGuard {

    private RbacGuard() {
    }

    static Authentication caller() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth instanceof AnonymousAuthenticationToken ? null : auth;
    }

    public static void requireHeld(Collection<String> authorities) {
        if (caller() == null || authorities == null || authorities.isEmpty()) {
            return;
        }
        Set<String> held = SsoAdminScope.authorities();
        if (held.contains(SsoAdminAuthorities.TENANTS) || held.containsAll(authorities)) {
            return;
        }
        throw new ForbiddenException(AuthoritiesErrors.INSUFFICIENT_PERMISSIONS);
    }

    public static void requireChangeHeld(Collection<String> before, Collection<String> after) {
        Set<String> changed = new TreeSet<>(before == null ? Set.of() : before);
        changed.addAll(after == null ? Set.of() : after);
        Set<String> kept = new TreeSet<>(before == null ? Set.of() : before);
        kept.retainAll(after == null ? Set.of() : after);
        changed.removeAll(kept);
        requireHeld(changed);
    }

    public static void requireNotSelf(Long userId) {
        Authentication auth = caller();
        if (auth != null && userId != null && String.valueOf(userId).equals(auth.getName())) {
            throw new ForbiddenException(AuthoritiesErrors.FORBIDDEN);
        }
    }

    public static Set<String> names(Collection<AuthorityEntity> authorities) {
        return authorities == null ? Set.of() : authorities.stream().filter(Objects::nonNull).map(AuthorityEntity::getName)
                .filter(Objects::nonNull).collect(Collectors.toCollection(TreeSet::new));
    }
}
