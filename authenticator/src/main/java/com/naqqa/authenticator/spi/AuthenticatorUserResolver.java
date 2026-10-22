package com.naqqa.authenticator.spi;

import java.util.Optional;

public interface AuthenticatorUserResolver {

    Optional<String> currentUserId();

    default String accountName(String userId) {
        return userId;
    }
}
