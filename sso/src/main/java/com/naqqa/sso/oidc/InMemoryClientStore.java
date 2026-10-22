package com.naqqa.sso.oidc;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryClientStore implements ClientStore {
    private final Map<String, ClientRegistration> clients = new ConcurrentHashMap<>();

    @Override
    public Optional<ClientRegistration> find(String clientId) {
        return clientId == null ? Optional.empty() : Optional.ofNullable(clients.get(clientId));
    }

    @Override
    public void save(ClientRegistration client) {
        clients.put(client.clientId(), client);
    }

    @Override
    public boolean delete(String clientId) {
        return clients.remove(clientId) != null;
    }
}
