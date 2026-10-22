package com.naqqa.authenticator.spi;

import com.naqqa.authenticator.domain.AuthenticatorEvent;

public interface AuthenticatorEventListener {

    AuthenticatorEventListener NO_OP = event -> {
    };

    void onEvent(AuthenticatorEvent event);
}
