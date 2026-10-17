package com.naqqa.sso.saml.xml;

import java.util.Objects;

public record XmlText(String text) implements XmlNode {
    public XmlText {
        Objects.requireNonNull(text);
    }

    public boolean isWhitespace() {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return false;
            }
        }
        return true;
    }
}
