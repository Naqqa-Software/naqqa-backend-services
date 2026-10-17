package com.naqqa.sso.saml.xml;

import com.naqqa.sso.SsoException;

public final class XmlEscape {
    private XmlEscape() {
    }

    public static void text(StringBuilder sb, String value) {
        check(value);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '\r' -> sb.append("&#xD;");
                default -> sb.append(c);
            }
        }
    }

    public static void attribute(StringBuilder sb, String value) {
        check(value);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '"' -> sb.append("&quot;");
                case '\t' -> sb.append("&#x9;");
                case '\n' -> sb.append("&#xA;");
                case '\r' -> sb.append("&#xD;");
                default -> sb.append(c);
            }
        }
    }

    public static String text(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        text(sb, value);
        return sb.toString();
    }

    public static String attribute(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        attribute(sb, value);
        return sb.toString();
    }

    private static void check(String value) {
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            if (!XmlParser.isXmlChar(cp) || (Character.isSurrogate((char) cp) && cp < 0x10000)) {
                throw new SsoException("XML_INVALID_CHAR");
            }
            i += Character.charCount(cp);
        }
    }
}
