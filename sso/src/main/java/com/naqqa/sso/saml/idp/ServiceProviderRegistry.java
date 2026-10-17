package com.naqqa.sso.saml.idp;

import java.util.Optional;

public interface ServiceProviderRegistry {
    Optional<ServiceProvider> find(String entityId);
}
