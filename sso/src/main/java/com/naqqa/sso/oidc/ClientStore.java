package com.naqqa.sso.oidc;

import java.util.Optional;

public interface ClientStore {
    Optional<ClientRegistration> find(String clientId);

    void save(ClientRegistration client);

    boolean delete(String clientId);
}
