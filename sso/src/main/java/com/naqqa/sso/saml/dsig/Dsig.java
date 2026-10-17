package com.naqqa.sso.saml.dsig;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.saml.xml.XmlElement;
import com.naqqa.sso.saml.xml.XmlNode;
import com.naqqa.sso.saml.xml.XmlText;

import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

public final class Dsig {
    public static final String NS = "http://www.w3.org/2000/09/xmldsig#";
    public static final String RSA_SHA256 = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256";
    public static final String ECDSA_SHA256 = "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha256";
    public static final String SHA256 = "http://www.w3.org/2001/04/xmlenc#sha256";
    public static final int MIN_RSA_BITS = 2048;

    private Dsig() {
    }

    public static byte[] base64(String text, String code) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                sb.append(c);
            }
        }
        try {
            return Base64.getDecoder().decode(sb.toString());
        } catch (IllegalArgumentException e) {
            throw new SsoException(code);
        }
    }

    public static String base64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    public static String jcaAlgorithm(String uri) {
        if (RSA_SHA256.equals(uri)) {
            return "SHA256withRSA";
        }
        if (ECDSA_SHA256.equals(uri)) {
            return "SHA256withECDSAinP1363Format";
        }
        throw new SsoException("DSIG_ALGORITHM");
    }

    public static boolean keyMatches(String uri, PublicKey key) {
        if (RSA_SHA256.equals(uri)) {
            return key instanceof RSAPublicKey;
        }
        if (ECDSA_SHA256.equals(uri)) {
            return key instanceof ECPublicKey;
        }
        return false;
    }

    public static void checkKey(PublicKey key) {
        if (key instanceof RSAPublicKey rsa) {
            if (rsa.getModulus().bitLength() < MIN_RSA_BITS) {
                throw new SsoException("DSIG_WEAK_KEY");
            }
            return;
        }
        if (key instanceof ECPublicKey ec) {
            if (ec.getParams().getCurve().getField().getFieldSize() != 256) {
                throw new SsoException("DSIG_UNSUPPORTED_KEY");
            }
            return;
        }
        throw new SsoException("DSIG_UNSUPPORTED_KEY");
    }

    static void requireOnlyWhitespaceText(XmlElement e) {
        for (XmlNode n : e.children()) {
            if (n instanceof XmlText t && !t.isWhitespace()) {
                throw new SsoException("DSIG_MALFORMED");
            }
        }
    }
}
