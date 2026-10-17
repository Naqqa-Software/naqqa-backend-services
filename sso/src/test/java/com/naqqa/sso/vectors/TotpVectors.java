package com.naqqa.sso.vectors;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.mfa.Base32;
import com.naqqa.sso.mfa.InMemoryTotpStepStore;
import com.naqqa.sso.mfa.OtpAlgorithm;
import com.naqqa.sso.mfa.OtpAuthUri;
import com.naqqa.sso.mfa.Totp;
import com.naqqa.sso.mfa.TotpConfig;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static com.naqqa.sso.vectors.VectorIo.m;

final class TotpVectors {
    private static final HexFormat HEX = HexFormat.of();
    private static final String SHA1_SECRET = "12345678901234567890";
    private static final String SHA256_SECRET = "12345678901234567890123456789012";
    private static final String SHA512_SECRET = "1234567890123456789012345678901234567890123456789012345678901234";

    private TotpVectors() {
    }

    private static String hex(String ascii) {
        return HEX.formatHex(ascii.getBytes(StandardCharsets.US_ASCII));
    }

    private static OtpAlgorithm alg(String name) {
        return OtpAlgorithm.valueOf(name);
    }

    private static TotpConfig config(Map<String, Object> in, int window) {
        return new TotpConfig(alg((String) in.get("algorithm")), ((Long) in.get("digits")).intValue(), ((Long) in.get("period")).intValue(), window);
    }

    private static Map<String, Object> generateInput(String secretHex, String algorithm, int digits, int period, long time) {
        return m("secretHex", secretHex, "algorithm", algorithm, "digits", (long) digits, "period", (long) period, "epochSeconds", time);
    }

    private static Map<String, Object> generateExpected(Map<String, Object> in) {
        TotpConfig c = config(in, 1);
        long t = (Long) in.get("epochSeconds");
        String code = Totp.generate(HEX.parseHex((String) in.get("secretHex")), t, c);
        return m("code", code, "step", Math.floorDiv(t, (long) c.stepSeconds()));
    }

    private static Map<String, Object> verifyExpected(Map<String, Object> in) {
        TotpConfig c = config(in, ((Long) in.get("window")).intValue());
        Clock clock = Clock.fixed(Instant.ofEpochSecond((Long) in.get("nowEpochSeconds")), ZoneOffset.UTC);
        InMemoryTotpStepStore store = new InMemoryTotpStepStore();
        Object last = in.get("lastAcceptedStep");
        if (last != null) {
            store.advance("u", (Long) last);
        }
        Totp.Result r = new Totp(clock, c, store).verify("u", HEX.parseHex((String) in.get("secretHex")), (String) in.get("code"));
        return m("valid", r.valid(), "step", r.step());
    }

    private static Map<String, Object> uriExpected(Map<String, Object> in) {
        try {
            byte[] secret = HEX.parseHex((String) in.get("secretHex"));
            String uri;
            if ("hotp".equals(in.get("type"))) {
                uri = OtpAuthUri.hotp((String) in.get("issuer"), (String) in.get("account"), secret, alg((String) in.get("algorithm")),
                        ((Long) in.get("digits")).intValue(), (Long) in.get("counter"));
            } else {
                uri = OtpAuthUri.totp((String) in.get("issuer"), (String) in.get("account"), secret, config(in, 1));
            }
            return m("ok", true, "uri", uri);
        } catch (SsoException e) {
            return m("ok", false, "error", e.code());
        }
    }

    private static Map<String, Object> base32Expected(Map<String, Object> in) {
        if ("encode".equals(in.get("op"))) {
            byte[] data = HEX.parseHex((String) in.get("dataHex"));
            return m("text", Base32.encode(data, Boolean.TRUE.equals(in.get("padding"))));
        }
        try {
            return m("ok", true, "dataHex", HEX.formatHex(Base32.decode((String) in.get("text"))));
        } catch (SsoException e) {
            return m("ok", false, "error", e.code());
        }
    }

    private static Map<String, Object> uri(String type, String issuer, String account, String secretHex, String algorithm, int digits, long periodOrCounter) {
        return "hotp".equals(type)
                ? m("type", type, "issuer", issuer, "account", account, "secretHex", secretHex, "algorithm", algorithm, "digits", (long) digits, "counter", periodOrCounter)
                : m("type", type, "issuer", issuer, "account", account, "secretHex", secretHex, "algorithm", algorithm, "digits", (long) digits, "period", periodOrCounter);
    }

    static Map<String, Object> build() {
        List<Object> generate = new ArrayList<>();
        long[] times = {59L, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L};
        String[][] rfc = {{"SHA1", SHA1_SECRET}, {"SHA256", SHA256_SECRET}, {"SHA512", SHA512_SECRET}};
        for (String[] r : rfc) {
            for (long t : times) {
                Map<String, Object> in = generateInput(hex(r[1]), r[0], 8, 30, t);
                generate.add(VectorIo.docCase("rfc6238-" + r[0].toLowerCase() + "-" + t, "RFC 6238 appendix B, 8 digits, 30 second step.", in, generateExpected(in)));
            }
        }
        for (long t : new long[]{59L, 1234567890L, 2000000000L}) {
            Map<String, Object> in = generateInput(hex(SHA1_SECRET), "SHA1", 6, 30, t);
            generate.add(VectorIo.docCase("default-sha1-6-" + t, "Authenticator app defaults: SHA1, 6 digits, 30 second step.", in, generateExpected(in)));
        }
        Map<String, Object> sixty = generateInput(hex(SHA256_SECRET), "SHA256", 7, 60, 1234567890L);
        generate.add(VectorIo.docCase("sha256-7-digits-60s", "Non default digits and period.", sixty, generateExpected(sixty)));

        long now = 1760522400L;
        String secret = hex(SHA1_SECRET);
        long step = Math.floorDiv(now, 30L);
        List<Object> verify = new ArrayList<>();
        String[][] verifyCases = {
                {"current-code", "0", "0", "Code of the current step."},
                {"previous-step-in-window", "-30", "0", "Code of the previous step, window is 1."},
                {"next-step-in-window", "30", "0", "Code of the next step, window is 1."},
                {"two-steps-back-outside-window", "-60", "0", "Code two steps old is outside window 1."},
                {"two-steps-ahead-outside-window", "60", "0", "Code two steps ahead is outside window 1."},
        };
        for (String[] vc : verifyCases) {
            Map<String, Object> in = generateInput(secret, "SHA1", 6, 30, now + Long.parseLong(vc[1]));
            String code = (String) generateExpected(in).get("code");
            Map<String, Object> vin = m("secretHex", secret, "algorithm", "SHA1", "digits", 6L, "period", 30L, "window", 1L,
                    "nowEpochSeconds", now, "code", code, "lastAcceptedStep", null);
            verify.add(VectorIo.docCase(vc[0], vc[3], vin, verifyExpected(vin)));
        }
        String currentCode = Totp.generate(HEX.parseHex(secret), now, new TotpConfig(OtpAlgorithm.SHA1, 6, 30, 1));
        Map<String, Object> replay = m("secretHex", secret, "algorithm", "SHA1", "digits", 6L, "period", 30L, "window", 1L,
                "nowEpochSeconds", now, "code", currentCode, "lastAcceptedStep", step);
        verify.add(VectorIo.docCase("replay-of-accepted-step", "The same step was already accepted, so the code is refused.", replay, verifyExpected(replay)));
        Map<String, Object> older = m("secretHex", secret, "algorithm", "SHA1", "digits", 6L, "period", 30L, "window", 1L,
                "nowEpochSeconds", now, "code", currentCode, "lastAcceptedStep", step - 1);
        verify.add(VectorIo.docCase("newer-than-last-accepted", "Last accepted step is older than the code step, accepted.", older, verifyExpected(older)));
        Map<String, Object> wrongLength = m("secretHex", secret, "algorithm", "SHA1", "digits", 6L, "period", 30L, "window", 1L,
                "nowEpochSeconds", now, "code", currentCode.substring(1), "lastAcceptedStep", null);
        verify.add(VectorIo.docCase("wrong-length", "Five digit code for a six digit config.", wrongLength, verifyExpected(wrongLength)));
        Map<String, Object> letters = m("secretHex", secret, "algorithm", "SHA1", "digits", 6L, "period", 30L, "window", 1L,
                "nowEpochSeconds", now, "code", "12345a", "lastAcceptedStep", null);
        verify.add(VectorIo.docCase("non-digit", "Code with a letter.", letters, verifyExpected(letters)));
        Map<String, Object> window0 = m("secretHex", secret, "algorithm", "SHA1", "digits", 6L, "period", 30L, "window", 0L,
                "nowEpochSeconds", now, "code", Totp.generate(HEX.parseHex(secret), now - 30, new TotpConfig(OtpAlgorithm.SHA1, 6, 30, 0)), "lastAcceptedStep", null);
        verify.add(VectorIo.docCase("window-zero-previous-step", "Window 0 accepts only the current step.", window0, verifyExpected(window0)));

        List<Object> uris = new ArrayList<>();
        Object[][] uriCases = {
                {"totp-basic", "Defaults, plain issuer and account.", uri("totp", "Naqqa", "alex@naqqa.com", secret, "SHA1", 6, 30)},
                {"totp-space-in-issuer", "Space is encoded as %20, never +.", uri("totp", "Naqqa Cloud", "alex@naqqa.com", secret, "SHA1", 6, 30)},
                {"totp-plus-and-at-in-account", "Plus and at sign in the account.", uri("totp", "Naqqa", "alex+test@naqqa.com", secret, "SHA1", 6, 30)},
                {"totp-unicode-account", "Non ASCII account is UTF-8 percent encoded.", uri("totp", "Naqqa", "ștefan@naqqa.com", secret, "SHA1", 6, 30)},
                {"totp-sha256-8-60", "SHA256, 8 digits, 60 second period.", uri("totp", "Naqqa", "alex@naqqa.com", hex(SHA256_SECRET), "SHA256", 8, 60)},
                {"totp-sha512", "SHA512 with a 64 byte secret.", uri("totp", "Naqqa", "alex@naqqa.com", hex(SHA512_SECRET), "SHA512", 6, 30)},
                {"hotp-counter", "HOTP uri with counter 5.", uri("hotp", "Naqqa", "alex@naqqa.com", secret, "SHA1", 6, 5)},
                {"reject-colon-in-issuer", "Issuer must not contain a colon.", uri("totp", "Na:qqa", "alex@naqqa.com", secret, "SHA1", 6, 30)},
                {"reject-empty-account", "Account must not be empty.", uri("totp", "Naqqa", "", secret, "SHA1", 6, 30)},
                {"reject-empty-issuer", "Issuer must not be empty.", uri("totp", "", "alex@naqqa.com", secret, "SHA1", 6, 30)},
        };
        for (Object[] c : uriCases) {
            @SuppressWarnings("unchecked") Map<String, Object> in = (Map<String, Object>) c[2];
            uris.add(VectorIo.docCase((String) c[0], (String) c[1], in, uriExpected(in)));
        }

        List<Object> base32 = new ArrayList<>();
        String[] plain = {"", "f", "fo", "foo", "foob", "fooba", "foobar"};
        for (String p : plain) {
            Map<String, Object> in = m("op", "encode", "dataHex", hex(p), "padding", false);
            base32.add(VectorIo.docCase("rfc4648-encode-" + (p.isEmpty() ? "empty" : p), "RFC 4648 section 10, no padding (as used in otpauth).", in, base32Expected(in)));
            Map<String, Object> padded = m("op", "encode", "dataHex", hex(p), "padding", true);
            base32.add(VectorIo.docCase("rfc4648-encode-padded-" + (p.isEmpty() ? "empty" : p), "RFC 4648 section 10, with padding.", padded, base32Expected(padded)));
        }
        String[][] decodes = {
                {"decode-unpadded", "MZXW6YTBOI", "Unpadded input is accepted."},
                {"decode-padded", "MZXW6YTBOI======", "Padded input is accepted."},
                {"decode-secret", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "Base32 of the RFC 6238 SHA1 secret."},
                {"reject-lowercase", "mzxw6ytboi", "Lowercase is rejected."},
                {"reject-space", "MZXW 6YTB", "Embedded space is rejected."},
                {"reject-digit-1", "MZXW1YTB", "1 is not in the alphabet."},
                {"reject-bad-padding-count", "MZXW6YTBOI=====", "Wrong amount of padding."},
                {"reject-impossible-length", "A", "A single character cannot encode a byte."},
                {"reject-nonzero-trailing-bits", "MZ", "Trailing bits must be zero (f encodes as MY)."},
                {"reject-padding-in-middle", "MZXW=YTB", "Padding only at the end."},
        };
        for (String[] d : decodes) {
            Map<String, Object> in = m("op", "decode", "text", d[1]);
            base32.add(VectorIo.docCase(d[0], d[2], in, base32Expected(in)));
        }

        return m("description", "RFC 6238 vectors, TOTP verification windows and replay, otpauth URIs and Base32 (RFC 4648).", "version", 1,
                "generate", generate, "verify", verify, "otpauth", uris, "base32", base32);
    }
}
