package com.naqqa.auth.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.naqqa.sso.crypto.Pem;
import com.naqqa.sso.util.Bytes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

public final class Jwts {

    private Jwts() {
    }

    public static JsonNode header(String jwt) {
        return parse(jwt.split("\\.")[0]);
    }

    public static JsonNode payload(String jwt) {
        return parse(jwt.split("\\.")[1]);
    }

    static JsonNode parse(String part) {
        try {
            return Browser.MAPPER.readTree(Base64.getUrlDecoder().decode(part));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static PrivateKey testPrivateKey() {
        try {
            return Pem.parsePrivateKey(Files.readString(Path.of(TestEnvInitializer.publicKeyPath()).resolveSibling("private.pem")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static PublicKey testPublicKey() {
        try {
            return Pem.parsePublicKey(Files.readString(Path.of(TestEnvInitializer.publicKeyPath())));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sign(String headerJson, String payloadJson, PrivateKey key) {
        try {
            String h = Bytes.b64url(Bytes.utf8(headerJson));
            String p = Bytes.b64url(Bytes.utf8(payloadJson));
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(key);
            s.update(Bytes.utf8(h + "." + p));
            return h + "." + p + "." + Bytes.b64url(s.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean verifiesWithJdk(String jwt, PublicKey key) {
        try {
            String[] parts = jwt.split("\\.");
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initVerify(key);
            s.update(Bytes.utf8(parts[0] + "." + parts[1]));
            return s.verify(Base64.getUrlDecoder().decode(parts[2]));
        } catch (Exception e) {
            return false;
        }
    }
}
