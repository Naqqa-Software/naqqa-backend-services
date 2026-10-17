package com.naqqa.auth.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;
import com.naqqa.sso.webauthn.Cbor;
import com.naqqa.sso.webauthn.CoseAlgorithm;
import com.naqqa.sso.webauthn.CoseKey;

import java.io.ByteArrayOutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SoftAuthenticator {

    public static final int UP = 0x01;
    public static final int UV = 0x04;
    public static final int AT = 0x40;
    public static final String ORIGIN = "http://localhost:4200";

    private final KeyPair keys;
    private final byte[] credentialId = Bytes.random(32);
    private byte[] userHandle;
    private long counter;
    public String origin = ORIGIN;

    public SoftAuthenticator() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            this.keys = g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String id() {
        return Bytes.b64url(credentialId);
    }

    static byte[] authData(String rpId, int flags, long count, byte[] attested) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(Hashes.sha256(Bytes.utf8(rpId)));
        out.write(flags);
        out.write((int) (count >>> 24));
        out.write((int) (count >>> 16));
        out.write((int) (count >>> 8));
        out.write((int) count);
        if (attested != null) {
            out.writeBytes(attested);
        }
        return out.toByteArray();
    }

    static byte[] clientData(String type, String challengeB64, String origin) {
        return Bytes.utf8("{\"type\":\"" + type + "\",\"challenge\":\"" + challengeB64 + "\",\"origin\":\"" + origin + "\",\"crossOrigin\":false}");
    }

    public String register(JsonNode options, int flags) {
        JsonNode pk = options.get("publicKey");
        String rpId = pk.get("rp").get("id").asText();
        userHandle = Bytes.b64urlDecode(pk.get("user").get("id").asText());
        byte[] cose = CoseKey.of(keys.getPublic(), CoseAlgorithm.ES256).encoded();
        byte[] attested = Bytes.concat(new byte[16], new byte[]{0, (byte) credentialId.length}, credentialId, cose);
        byte[] ad = authData(rpId, flags | AT, counter, attested);
        Map<Object, Object> att = new LinkedHashMap<>();
        att.put("fmt", "none");
        att.put("attStmt", new LinkedHashMap<>());
        att.put("authData", ad);
        byte[] cd = clientData("webauthn.create", pk.get("challenge").asText(), origin);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("clientDataJSON", Bytes.b64url(cd));
        response.put("attestationObject", Bytes.b64url(Cbor.encode(att)));
        response.put("transports", List.of("internal"));
        Map<String, Object> cred = new LinkedHashMap<>();
        cred.put("id", id());
        cred.put("rawId", id());
        cred.put("type", "public-key");
        cred.put("response", response);
        return write(cred);
    }

    public String assertion(JsonNode options, int flags) {
        JsonNode pk = options.get("publicKey");
        String rpId = pk.get("rpId").asText();
        counter++;
        byte[] ad = authData(rpId, flags, counter, null);
        byte[] cd = clientData("webauthn.get", pk.get("challenge").asText(), origin);
        byte[] sig;
        try {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initSign(keys.getPrivate());
            s.update(Bytes.concat(ad, Hashes.sha256(cd)));
            sig = s.sign();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("clientDataJSON", Bytes.b64url(cd));
        response.put("authenticatorData", Bytes.b64url(ad));
        response.put("signature", Bytes.b64url(sig));
        if (userHandle != null) {
            response.put("userHandle", Bytes.b64url(userHandle));
        }
        Map<String, Object> cred = new LinkedHashMap<>();
        cred.put("id", id());
        cred.put("rawId", id());
        cred.put("type", "public-key");
        cred.put("response", response);
        return write(cred);
    }

    static String write(Object o) {
        try {
            return Browser.MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
