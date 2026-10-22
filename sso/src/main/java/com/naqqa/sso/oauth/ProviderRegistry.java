package com.naqqa.sso.oauth;

import com.naqqa.sso.SsoException;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public interface ProviderRegistry {
    Optional<ProviderConfig> find(String providerId);

    default boolean singleProvider() {
        return false;
    }

    static ProviderRegistry of(Collection<ProviderConfig> configs) {
        Map<String, ProviderConfig> m = new LinkedHashMap<>();
        for (ProviderConfig c : configs) {
            if (m.put(c.id(), c) != null) {
                throw new SsoException("oauth.provider_duplicate");
            }
        }
        for (ProviderConfig c : m.values()) {
            if (c.pkce()) {
                continue;
            }
            for (ProviderConfig other : m.values()) {
                if (other != c && other.redirectUri().equals(c.redirectUri())) {
                    throw new SsoException("oauth.redirect_uri_shared");
                }
            }
        }
        boolean single = m.size() == 1;
        return new ProviderRegistry() {
            @Override
            public Optional<ProviderConfig> find(String providerId) {
                return Optional.ofNullable(m.get(providerId));
            }

            @Override
            public boolean singleProvider() {
                return single;
            }
        };
    }
}
