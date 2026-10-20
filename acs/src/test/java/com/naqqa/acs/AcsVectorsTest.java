package com.naqqa.acs;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class AcsVectorsTest {
    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean cond, String detail) {
        if (cond) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + (detail == null ? "" : " :: " + detail));
        }
    }

    @SuppressWarnings("unchecked")
    static int verify(String[] args) throws Exception {
        passed = 0;
        failed = 0;
        String text = Files.readString(Path.of(args.length > 0 ? args[0] : "src/test/resources/acs-vectors.json"), StandardCharsets.UTF_8);
        Map<String, Object> v = (Map<String, Object>) Json.parse(text);
        long now = (Long) v.get("now");
        String site = (String) v.get("siteId");
        long tol = (Long) v.get("clockToleranceMs");
        Map<String, String> keys = new java.util.HashMap<>();
        for (Object k : (List<Object>) v.get("keys")) {
            Map<String, Object> km = (Map<String, Object>) k;
            keys.put((String) km.get("kid"), (String) km.get("publicKey"));
        }
        Map<String, Object> session = (Map<String, Object>) v.get("session");
        byte[] secret = AcsVerifier.hex((String) session.get("secret"));
        AcsVerifier ver = new AcsVerifier(keys, site, secret, tol);

        for (Object o : (List<Object>) v.get("attestation")) {
            Map<String, Object> c = (Map<String, Object>) o;
            AcsVerifier.Result r = ver.verifyAttestation((String) c.get("token"), now);
            String name = "attestation/" + c.get("name");
            check(name + " ok", r.ok == (Boolean) c.get("ok"), "got ok=" + r.ok + " reason=" + r.reason);
            if (c.containsKey("reason")) check(name + " reason", c.get("reason").equals(r.reason), "got " + r.reason);
            if (Boolean.TRUE.equals(c.get("ok"))) {
                check(name + " sub", c.get("sub").equals(r.payload.get("sub")), null);
                check(name + " risk", ((Long) c.get("risk")).doubleValue() == ((Number) r.payload.get("risk")).doubleValue(), null);
            }
        }

        for (Object o : (List<Object>) v.get("action")) {
            Map<String, Object> c = (Map<String, Object>) o;
            AcsVerifier fresh = new AcsVerifier(keys, site, secret, tol);
            AcsVerifier.Result r = fresh.verifyAction((String) c.get("token"), now, (String) c.get("act"), (String) c.get("sub"));
            String name = "action/" + c.get("name");
            check(name + " ok", r.ok == (Boolean) c.get("ok"), "got ok=" + r.ok + " reason=" + r.reason);
            if (c.containsKey("reason")) check(name + " reason", c.get("reason").equals(r.reason), "got " + r.reason);
            if (Boolean.TRUE.equals(c.get("ok"))) {
                AcsVerifier.Result again = fresh.verifyAction((String) c.get("token"), now, (String) c.get("act"), (String) c.get("sub"));
                check(name + " single-use", !again.ok && "replay".equals(again.reason), "got " + again.reason);
            }
        }

        String sub = (String) session.get("sub");
        check("session/key", AcsVerifier.toHex(ver.sessionKey(sub)).equals(session.get("sessionKey")), AcsVerifier.toHex(ver.sessionKey(sub)));
        Map<String, Object> rq = (Map<String, Object>) session.get("request");
        byte[] framed = AcsVerifier.framed("acs-sess-v1", (String) rq.get("method"), (String) rq.get("path"), (String) rq.get("query"), Long.toString((Long) rq.get("ts")), (String) rq.get("nonce"));
        check("session/framed", AcsVerifier.toHex(framed).equals(session.get("framedHex")), null);
        String header = (String) session.get("header");
        long ts = (Long) rq.get("ts");
        AcsVerifier s1 = new AcsVerifier(keys, site, secret, tol);
        check("session/valid", s1.verifySessionSig(sub, header, "GET", (String) rq.get("path"), (String) rq.get("query"), ts).ok, null);
        check("session/replay", "replay".equals(s1.verifySessionSig(sub, header, "GET", (String) rq.get("path"), (String) rq.get("query"), ts).reason), null);
        AcsVerifier s2 = new AcsVerifier(keys, site, secret, tol);
        check("session/query-with-?", s2.verifySessionSig(sub, header, "GET", (String) rq.get("path"), "?" + rq.get("query"), ts).ok, null);
        AcsVerifier s3 = new AcsVerifier(keys, site, secret, tol);
        check("session/stale", "stale".equals(s3.verifySessionSig(sub, header, "GET", (String) rq.get("path"), (String) rq.get("query"), ts + 31_000).reason), null);
        check("session/tampered-path", "bad".equals(s3.verifySessionSig(sub, header, "GET", "/api/products", (String) rq.get("query"), ts).reason), null);
        check("session/other-sub", "bad".equals(s3.verifySessionSig("other", header, "GET", (String) rq.get("path"), (String) rq.get("query"), ts).reason), null);
        check("session/garbage", !s3.verifySessionSig(sub, "v1.x.y.z", "GET", "/", "", ts).ok, null);

        Map<String, Object> gv = (Map<String, Object>) v.get("guard");
        AcsVerifier g1 = new AcsVerifier(keys, site, secret, tol);
        long gts = (Long) gv.get("ts");
        check("guard/origin-key", AcsVerifier.toHex(g1.originKey()).equals(gv.get("originKey")), null);
        check("guard/valid", g1.verifyGuardAssertion((String) gv.get("header"), "POST", (String) gv.get("path"), (String) gv.get("query"), gts).ok, null);
        check("guard/replay", "replay".equals(g1.verifyGuardAssertion((String) gv.get("header"), "POST", (String) gv.get("path"), (String) gv.get("query"), gts).reason), null);
        AcsVerifier g2 = new AcsVerifier(keys, site, secret, tol);
        check("guard/other-path", "bad".equals(g2.verifyGuardAssertion((String) gv.get("header"), "POST", "/api/auth/login", "", gts).reason), null);
        check("guard/other-method", "bad".equals(g2.verifyGuardAssertion((String) gv.get("header"), "GET", (String) gv.get("path"), "", gts).reason), null);
        check("guard/stale", "stale".equals(g2.verifyGuardAssertion((String) gv.get("header"), "POST", (String) gv.get("path"), "", gts + 31_000).reason), null);
        check("guard/no-secret", !new AcsVerifier(keys, site, null, tol).verifyGuardAssertion((String) gv.get("header"), "POST", (String) gv.get("path"), "", gts).ok, null);

        byte[] okm = AcsVerifier.hkdf(AcsVerifier.hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), AcsVerifier.hex("000102030405060708090a0b0c"), AcsVerifier.hex("f0f1f2f3f4f5f6f7f8f9"), 42);
        check("hkdf/rfc5869", AcsVerifier.toHex(okm).equals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), AcsVerifier.toHex(okm));
        check("b64/padding-rejected", AcsVerifier.b64("YQ==") == null, null);
        check("b64/plus-rejected", AcsVerifier.b64("a+b") == null, null);
        check("b64/len1-rejected", AcsVerifier.b64("abcde") == null, null);

        NonceStore ns = new NonceStore(60_000, 2);
        check("nonce/fresh", ns.checkAndStore("a", 0) && ns.checkAndStore("b", 0), null);
        check("nonce/full-fails-closed", !ns.checkAndStore("c", 0), null);
        check("nonce/replay", !ns.checkAndStore("a", 1), null);
        check("nonce/expiry", ns.checkAndStore("a", 60_001), null);

        for (Object o : (List<Object>) v.get("paths")) {
            Map<String, Object> c = (Map<String, Object>) o;
            PathCanon p = PathCanon.of((String) c.get("input"));
            String n = "path/" + c.get("input");
            check(n + " path", c.get("path").equals(p.path), "got " + p.path);
            check(n + " suspicious", c.get("suspicious").equals(p.suspicious), "got " + p.suspicious);
            check(n + " ambiguous", c.get("ambiguous").equals(p.ambiguous), "got " + p.ambiguous);
        }
        Map<String, Object> b = (Map<String, Object>) v.get("binding");
        AcsVerifier bv = new AcsVerifier(keys, site, secret, tol, AcsVerifier.hex((String) b.get("hashKey")));
        for (Object o : (List<Object>) b.get("uas")) {
            Map<String, Object> c = (Map<String, Object>) o;
            check("uah/" + c.get("ua"), c.get("uah").equals(bv.keyedHash((String) c.get("ua"))), null);
        }
        for (Object o : (List<Object>) b.get("ips")) {
            Map<String, Object> c = (Map<String, Object>) o;
            String cls = IpUtil.ipClass((String) c.get("ip"));
            check("ipClass/" + c.get("ip"), java.util.Objects.equals(c.get("ipClass"), cls), "got " + cls);
            check("ipc/" + c.get("ip"), c.get("ipcHash").equals(bv.keyedHash(cls == null ? "unknown" : cls)), null);
        }
        for (Object o : (List<Object>) b.get("cases")) {
            Map<String, Object> c = (Map<String, Object>) o;
            String n = "binding/" + c.get("name");
            AcsVerifier.Result r = bv.verifyAttestationBound((String) c.get("token"), now, (String) c.get("ua"), (String) c.get("ip"), false);
            check(n + " ok", r.ok == (Boolean) c.get("ok"), "got ok=" + r.ok + " reason=" + r.reason);
            check(n + " reason", java.util.Objects.equals(c.get("reason"), r.reason), "got " + r.reason);
            AcsVerifier.Result st = bv.verifyAttestationBound((String) c.get("token"), now, (String) c.get("ua"), (String) c.get("ip"), true);
            boolean strictOk = c.containsKey("strictOk") ? (Boolean) c.get("strictOk") : (Boolean) c.get("ok");
            check(n + " strict ok", st.ok == strictOk, "got " + st.ok + " " + st.reason);
            if (c.containsKey("strictReason")) check(n + " strict reason", c.get("strictReason").equals(st.reason), "got " + st.reason);
        }
        Map<String, Object> ci = (Map<String, Object>) v.get("clientIp");
        List<String> trusted = new java.util.ArrayList<>();
        for (Object t : (List<Object>) ci.get("trusted")) trusted.add((String) t);
        for (Object o : (List<Object>) ci.get("cases")) {
            Map<String, Object> c = (Map<String, Object>) o;
            String got = IpUtil.resolveClientIp((String) c.get("remote"), (String) c.get("xff"), trusted);
            check("clientIp/" + c.get("remote") + "|" + c.get("xff"), c.get("expected").equals(got), "got " + got);
        }
        System.out.println("java vectors: " + passed + " passed, " + failed + " failed");
        return failed;
    }

    @Test
    void javaPortMatchesTheTypeScriptVectors() throws Exception {
        assertEquals(0, verify(new String[0]));
    }

    public static void main(String[] args) throws Exception {
        System.exit(verify(args) == 0 ? 0 : 1);
    }
}
