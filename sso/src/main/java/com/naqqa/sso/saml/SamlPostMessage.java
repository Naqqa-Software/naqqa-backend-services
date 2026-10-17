package com.naqqa.sso.saml;

import java.util.Objects;

public record SamlPostMessage(String destination, String parameter, String value, String relayState, String messageId) {
    public SamlPostMessage {
        Objects.requireNonNull(destination);
        Objects.requireNonNull(parameter);
        Objects.requireNonNull(value);
        Objects.requireNonNull(messageId);
    }

    public String htmlForm() {
        return SamlBindings.postForm(destination, parameter, value, relayState);
    }
}
