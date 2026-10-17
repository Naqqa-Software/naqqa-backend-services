package com.naqqa.sso.saml.xml;

import java.util.Objects;

public record XmlAttribute(String prefix, String localName, String namespaceUri, String value) {
    public XmlAttribute {
        Objects.requireNonNull(prefix);
        Objects.requireNonNull(localName);
        Objects.requireNonNull(namespaceUri);
        Objects.requireNonNull(value);
    }

    public String qualifiedName() {
        return prefix.isEmpty() ? localName : prefix + ":" + localName;
    }
}
