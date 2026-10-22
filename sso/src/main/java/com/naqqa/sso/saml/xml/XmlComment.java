package com.naqqa.sso.saml.xml;

import java.util.Objects;

public record XmlComment(String text) implements XmlNode {
    public XmlComment {
        Objects.requireNonNull(text);
    }
}
