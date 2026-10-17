package com.naqqa.sso.saml;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.saml.dsig.Dsig;
import com.naqqa.sso.saml.dsig.XmlSignatureVerifier;
import com.naqqa.sso.saml.dsig.XmlSigner;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public final class SamlBindings {
    public static final int MAX_INFLATED = 256 * 1024;
    public static final int MAX_QUERY = 128 * 1024;
    public static final int MAX_POST = 512 * 1024;
    public static final int MAX_RELAY_STATE = 1024;
    private static final Set<String> KNOWN = Set.of("SAMLRequest", "SAMLResponse", "RelayState", "SigAlg", "Signature");

    private SamlBindings() {
    }

    public record RedirectMessage(byte[] xml, String relayState, String sigAlg, byte[] signature, byte[] signedContent) {
        public boolean signed() {
            return signature != null;
        }
    }

    public static RedirectMessage decodeRedirect(String rawQuery, String param, int maxInflated) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            throw new SsoException("SAML_BINDING");
        }
        if (rawQuery.length() > MAX_QUERY) {
            throw new SsoException("SAML_MESSAGE_TOO_LARGE");
        }
        Map<String, String> raw = new HashMap<>();
        for (String pair : rawQuery.split("&", -1)) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            if (KNOWN.contains(name) && raw.put(name, value) != null) {
                throw new SsoException("SAML_BINDING");
            }
        }
        String message = raw.get(param);
        if (message == null || message.isEmpty()) {
            throw new SsoException("SAML_BINDING");
        }
        String relayState = raw.containsKey("RelayState") ? urlDecode(raw.get("RelayState")) : null;
        checkRelayState(relayState);
        byte[] xml = inflate(Dsig.base64(urlDecode(message), "SAML_BINDING"), maxInflated);
        String sigAlgRaw = raw.get("SigAlg");
        String sigRaw = raw.get("Signature");
        if ((sigAlgRaw == null) != (sigRaw == null)) {
            throw new SsoException("SAML_BINDING");
        }
        if (sigRaw == null) {
            return new RedirectMessage(xml, relayState, null, null, null);
        }
        StringBuilder signed = new StringBuilder();
        signed.append(param).append('=').append(message);
        if (raw.containsKey("RelayState")) {
            signed.append("&RelayState=").append(raw.get("RelayState"));
        }
        signed.append("&SigAlg=").append(sigAlgRaw);
        byte[] signature = Dsig.base64(urlDecode(sigRaw), "SAML_BINDING");
        return new RedirectMessage(xml, relayState, urlDecode(sigAlgRaw), signature,
                signed.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static void verifyRedirectSignature(RedirectMessage message, XmlSignatureVerifier verifier) {
        if (!message.signed()) {
            throw new SsoException("SAML_SIGNATURE_REQUIRED");
        }
        if (!Dsig.RSA_SHA256.equals(message.sigAlg()) && !Dsig.ECDSA_SHA256.equals(message.sigAlg())) {
            throw new SsoException("DSIG_ALGORITHM");
        }
        if (!verifier.verifyRaw(message.sigAlg(), message.signedContent(), message.signature())) {
            throw new SsoException("DSIG_SIGNATURE_INVALID");
        }
    }

    public static String encodeRedirect(String location, String param, byte[] xml, String relayState, XmlSigner signer) {
        checkRelayState(relayState);
        StringBuilder q = new StringBuilder();
        q.append(param).append('=').append(urlEncode(Base64.getEncoder().encodeToString(deflate(xml))));
        if (relayState != null) {
            q.append("&RelayState=").append(urlEncode(relayState));
        }
        if (signer != null) {
            q.append("&SigAlg=").append(urlEncode(signer.algorithm()));
            byte[] sig = signer.signRaw(q.toString().getBytes(StandardCharsets.UTF_8));
            q.append("&Signature=").append(urlEncode(Base64.getEncoder().encodeToString(sig)));
        }
        return location + (location.indexOf('?') >= 0 ? "&" : "?") + q;
    }

    public static byte[] decodePost(String value, int maxBytes) {
        if (value == null || value.isEmpty()) {
            throw new SsoException("SAML_BINDING");
        }
        if (value.length() > MAX_POST || value.length() / 4L * 3 > (long) maxBytes + 4) {
            throw new SsoException("SAML_MESSAGE_TOO_LARGE");
        }
        byte[] out = Dsig.base64(value, "SAML_BINDING");
        if (out.length > maxBytes) {
            throw new SsoException("SAML_MESSAGE_TOO_LARGE");
        }
        return out;
    }

    public static String encodePost(byte[] xml) {
        return Base64.getEncoder().encodeToString(xml);
    }

    public static String postForm(String action, String param, String value, String relayState) {
        checkRelayState(relayState);
        StringBuilder sb = new StringBuilder(value.length() + 512);
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>SAML</title></head>")
                .append("<body onload=\"document.forms[0].submit()\"><form method=\"post\" action=\"")
                .append(html(action)).append("\"><input type=\"hidden\" name=\"").append(html(param))
                .append("\" value=\"").append(html(value)).append("\"/>");
        if (relayState != null) {
            sb.append("<input type=\"hidden\" name=\"RelayState\" value=\"").append(html(relayState)).append("\"/>");
        }
        sb.append("<noscript><button type=\"submit\">Continue</button></noscript></form></body></html>");
        return sb.toString();
    }

    public static void checkRelayState(String relayState) {
        if (relayState != null && relayState.getBytes(StandardCharsets.UTF_8).length > MAX_RELAY_STATE) {
            throw new SsoException("SAML_RELAY_STATE_TOO_LONG");
        }
    }

    public static byte[] inflate(byte[] data, int maxOutput) {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxOutput, data.length * 4 + 64));
            byte[] buf = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        throw new SsoException("SAML_INFLATE_FAILED");
                    }
                }
                if (out.size() + n > maxOutput) {
                    throw new SsoException("SAML_INFLATE_LIMIT");
                }
                out.write(buf, 0, n);
            }
            if (inflater.getRemaining() > 0) {
                throw new SsoException("SAML_INFLATE_FAILED");
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new SsoException("SAML_INFLATE_FAILED");
        } finally {
            inflater.end();
        }
    }

    public static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try {
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!deflater.finished()) {
                int n = deflater.deflate(buf);
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    public static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public static String urlDecode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '+') {
                out.write(' ');
            } else if (c == '%') {
                if (i + 2 >= s.length()) {
                    throw new SsoException("SAML_BINDING");
                }
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi < 0 || lo < 0 || s.charAt(i + 1) > 'f' || s.charAt(i + 2) > 'f') {
                    throw new SsoException("SAML_BINDING");
                }
                out.write(hi * 16 + lo);
                i += 2;
            } else if (c < 0x80) {
                out.write(c);
            } else {
                throw new SsoException("SAML_BINDING");
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(out.toByteArray())).toString();
        } catch (CharacterCodingException e) {
            throw new SsoException("SAML_BINDING");
        }
    }

    private static String html(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
