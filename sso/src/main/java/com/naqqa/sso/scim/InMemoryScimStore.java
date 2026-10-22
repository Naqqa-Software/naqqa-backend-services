package com.naqqa.sso.scim;

import com.naqqa.sso.SsoException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

abstract class InMemoryScimStore implements ScimResourceStore {
    private final Map<String, Map<String, ScimResource>> byTenant = new HashMap<>();
    private final String uniqueAttribute;

    InMemoryScimStore(String uniqueAttribute) {
        this.uniqueAttribute = uniqueAttribute;
    }

    private String key(ScimResource r) {
        Object v = r.attributes().get(uniqueAttribute);
        return v instanceof String s ? s.toLowerCase(Locale.ROOT) : null;
    }

    private void checkUnique(Map<String, ScimResource> tenant, ScimResource candidate) {
        String k = key(candidate);
        if (k == null) {
            return;
        }
        for (ScimResource other : tenant.values()) {
            if (!other.id().equals(candidate.id()) && k.equals(key(other))) {
                throw new SsoException("scim.uniqueness");
            }
        }
    }

    @Override
    public synchronized Optional<ScimResource> find(String tenantId, String id) {
        Map<String, ScimResource> t = byTenant.get(tenantId);
        return t == null ? Optional.empty() : Optional.ofNullable(t.get(id));
    }

    @Override
    public synchronized List<ScimResource> list(String tenantId) {
        Map<String, ScimResource> t = byTenant.get(tenantId);
        return t == null ? List.of() : new ArrayList<>(t.values());
    }

    @Override
    public synchronized ScimResource insert(ScimResource resource) {
        Map<String, ScimResource> t = byTenant.computeIfAbsent(resource.tenantId(), x -> new LinkedHashMap<>());
        if (t.containsKey(resource.id())) {
            throw new SsoException("scim.uniqueness");
        }
        checkUnique(t, resource);
        t.put(resource.id(), resource);
        return resource;
    }

    @Override
    public synchronized ScimResource update(ScimResource next, long expectedVersion) {
        Map<String, ScimResource> t = byTenant.get(next.tenantId());
        ScimResource current = t == null ? null : t.get(next.id());
        if (current == null) {
            throw new SsoException("scim.not_found");
        }
        if (current.version() != expectedVersion || next.version() != expectedVersion + 1) {
            throw new SsoException("scim.version_conflict");
        }
        checkUnique(t, next);
        t.put(next.id(), next);
        return next;
    }

    @Override
    public synchronized boolean delete(String tenantId, String id) {
        Map<String, ScimResource> t = byTenant.get(tenantId);
        return t != null && t.remove(id) != null;
    }

    synchronized Optional<ScimResource> byUnique(String tenantId, String value) {
        if (value == null) {
            return Optional.empty();
        }
        String k = value.toLowerCase(Locale.ROOT);
        for (ScimResource r : list(tenantId)) {
            if (k.equals(key(r))) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }
}
