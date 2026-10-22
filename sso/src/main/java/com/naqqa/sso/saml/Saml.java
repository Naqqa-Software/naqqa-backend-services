package com.naqqa.sso.saml;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.saml.xml.XmlElement;
import com.naqqa.sso.saml.xml.XmlParser;
import com.naqqa.sso.util.Bytes;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

public final class Saml {
    public static final String ASSERTION_NS = "urn:oasis:names:tc:SAML:2.0:assertion";
    public static final String PROTOCOL_NS = "urn:oasis:names:tc:SAML:2.0:protocol";
    public static final String METADATA_NS = "urn:oasis:names:tc:SAML:2.0:metadata";
    public static final String BINDING_REDIRECT = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect";
    public static final String BINDING_POST = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST";
    public static final String NAMEID_EMAIL = "urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress";
    public static final String NAMEID_PERSISTENT = "urn:oasis:names:tc:SAML:2.0:nameid-format:persistent";
    public static final String NAMEID_TRANSIENT = "urn:oasis:names:tc:SAML:2.0:nameid-format:transient";
    public static final String NAMEID_UNSPECIFIED = "urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified";
    public static final String NAMEID_ENTITY = "urn:oasis:names:tc:SAML:2.0:nameid-format:entity";
    public static final String STATUS_SUCCESS = "urn:oasis:names:tc:SAML:2.0:status:Success";
    public static final String STATUS_REQUESTER = "urn:oasis:names:tc:SAML:2.0:status:Requester";
    public static final String STATUS_RESPONDER = "urn:oasis:names:tc:SAML:2.0:status:Responder";
    public static final String STATUS_AUTHN_FAILED = "urn:oasis:names:tc:SAML:2.0:status:AuthnFailed";
    public static final String STATUS_NO_PASSIVE = "urn:oasis:names:tc:SAML:2.0:status:NoPassive";
    public static final String STATUS_INVALID_NAMEID_POLICY = "urn:oasis:names:tc:SAML:2.0:status:InvalidNameIDPolicy";
    public static final String STATUS_PARTIAL_LOGOUT = "urn:oasis:names:tc:SAML:2.0:status:PartialLogout";
    public static final String CM_BEARER = "urn:oasis:names:tc:SAML:2.0:cm:bearer";
    public static final String AC_PASSWORD_PROTECTED = "urn:oasis:names:tc:SAML:2.0:ac:classes:PasswordProtectedTransport";
    public static final String AC_UNSPECIFIED = "urn:oasis:names:tc:SAML:2.0:ac:classes:unspecified";
    public static final String AC_MFA = "https://refeds.org/profile/mfa";
    public static final String ATTRNAME_BASIC = "urn:oasis:names:tc:SAML:2.0:attrname-format:basic";
    public static final String ATTRNAME_URI = "urn:oasis:names:tc:SAML:2.0:attrname-format:uri";

    private Saml() {
    }

    public static String newId() {
        return "_" + Bytes.hex(Bytes.random(20));
    }

    public static String requireId(String id) {
        if (id == null || id.length() > 256 || !XmlParser.isNcName(id)) {
            throw new SsoException("SAML_INVALID_ID");
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.';
            if (!ok) {
                throw new SsoException("SAML_INVALID_ID");
            }
        }
        return id;
    }

    public static String time(Instant instant) {
        return DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS));
    }

    public static Instant parseTime(String value) {
        if (value == null || !value.endsWith("Z") || value.length() > 40) {
            throw new SsoException("SAML_INVALID_TIME");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new SsoException("SAML_INVALID_TIME");
        }
    }

    public static Instant optionalTime(XmlElement e, String attribute) {
        String v = e.attribute(attribute);
        return v == null ? null : parseTime(v);
    }

    public static String requiredAttribute(XmlElement e, String name) {
        String v = e.attribute(name);
        if (v == null || v.isEmpty()) {
            throw new SsoException("SAML_MISSING_ATTRIBUTE");
        }
        return v;
    }

    public static String trimmedText(XmlElement e) {
        String t = e.text();
        int a = 0;
        int b = t.length();
        while (a < b && isWs(t.charAt(a))) {
            a++;
        }
        while (b > a && isWs(t.charAt(b - 1))) {
            b--;
        }
        return t.substring(a, b);
    }

    private static boolean isWs(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }
}
