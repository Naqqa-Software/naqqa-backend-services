package com.naqqa.elasticsearch.security.authc;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemorySecurityIndexStore implements SecurityIndexStore {

    private final Map<String, Map<String, Object>> users = new ConcurrentHashMap<>();

    @Override
    public Optional<Map<String, Object>> getUser(String username) {
        return Optional.ofNullable(users.get(username));
    }

    @Override
    public void putUser(String username, Map<String, Object> userDocument) {
        users.put(username, userDocument);
    }

    @Override
    public void removeUser(String username) {
        users.remove(username);
    }

    @Override
    public Collection<String> listUsernames() {
        return users.keySet();
    }
}
