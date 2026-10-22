package com.naqqa.sso.saml.idp;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryServiceProviderRegistry implements ServiceProviderRegistry {
    private final Map<String, ServiceProvider> providers = new ConcurrentHashMap<>();

    public InMemoryServiceProviderRegistry register(ServiceProvider sp) {
        providers.put(sp.entityId(), sp);
        return this;
    }

    public void remove(String entityId) {
        providers.remove(entityId);
    }

    @Override
    public Optional<ServiceProvider> find(String entityId) {
        return entityId == null ? Optional.empty() : Optional.ofNullable(providers.get(entityId));
    }
}
