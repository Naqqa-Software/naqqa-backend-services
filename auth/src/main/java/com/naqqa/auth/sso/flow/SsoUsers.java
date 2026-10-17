package com.naqqa.auth.sso.flow;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.repository.UserRepository;
import com.naqqa.auth.security.AuthPrincipal;
import com.naqqa.auth.sso.support.SsoApiException;
import com.naqqa.auth.sso.support.SsoWeb;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class SsoUsers {

    private final UserRepository userRepository;

    public UserEntity require(AuthPrincipal principal) {
        Long id = principal.getUserId();
        UserEntity user = id == null ? null : userRepository.findById(id).orElse(null);
        if (user == null || !user.isEnabled()) {
            throw SsoApiException.unauthorized("UNAUTHORIZED", "Authentication required");
        }
        return user;
    }

    public UserEntity current() {
        return require(SsoWeb.requirePrincipal());
    }

    public UserEntity currentOwn() {
        return require(SsoWeb.requireOwnSession());
    }

    public static Long roleId(AuthPrincipal principal) {
        Object role = principal.getClaims().get("role");
        if (role instanceof java.util.Map<?, ?> m && m.get("id") instanceof Number n) {
            return n.longValue();
        }
        return null;
    }
}
