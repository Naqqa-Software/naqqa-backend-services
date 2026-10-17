package com.naqqa.sso.saml.xml;

import java.util.Objects;

public record XmlNamespace(String prefix, String uri) {
    public XmlNamespace {
        Objects.requireNonNull(prefix);
        Objects.requireNonNull(uri);
    }
}
