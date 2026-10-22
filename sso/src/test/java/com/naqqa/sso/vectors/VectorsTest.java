package com.naqqa.sso.vectors;

import com.naqqa.sso.json.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorsTest {
    static {
        if (VectorIo.writeMode()) {
            Path dir = VectorIo.dir();
            VectorGenerator.generate(dir).forEach((name, text) -> VectorIo.write(dir.resolve(name), text));
        }
    }

    private static Map<String, Object> doc(String file) {
        return Json.parseObject(VectorIo.read(VectorIo.dir().resolve(file)), VectorIo.LIMITS);
    }

    private static Map<String, Object> caseNamed(Map<String, Object> doc, String listKey, String name) {
        for (Object o : Json.asList(doc.get(listKey))) {
            Map<String, Object> c = Json.asObject(o);
            if (name.equals(c.get("name"))) {
                return c;
            }
        }
        throw new AssertionError("missing case " + name);
    }

    @Test
    @DisplayName("SSO-T-vectors-001 committed vector files match what the implementation produces (write with -Dsso.vectors.write=true)")
    void committedFilesMatchImplementation() {
        Path dir = VectorIo.dir();
        for (Map.Entry<String, String> e : VectorGenerator.generate(dir).entrySet()) {
            Path file = dir.resolve(e.getKey());
            assertTrue(Files.exists(file), "missing vector file " + file + ", run with -Dsso.vectors.write=true");
            assertEquals(VectorIo.read(file), e.getValue(), "vector drift in " + e.getKey()
                    + ", implementation behaviour changed; review and regenerate with -Dsso.vectors.write=true");
        }
    }

    @Test
    @DisplayName("SSO-T-vectors-002 committed vectors carry the published RFC values independently of the generator")
    void rfcValuesAreCommitted() {
        Map<String, Object> pkce = doc("pkce.json");
        Map<String, Object> rfcPkce = Json.asObject(caseNamed(pkce, "cases", "rfc7636-appendix-b").get("expected"));
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", rfcPkce.get("challenge"));
        assertEquals(Boolean.TRUE, rfcPkce.get("verifies"));

        Map<String, Object> totp = doc("totp.json");
        String[][] rfc = {
                {"rfc6238-sha1-59", "94287082"}, {"rfc6238-sha1-1111111109", "07081804"}, {"rfc6238-sha1-1111111111", "14050471"},
                {"rfc6238-sha1-1234567890", "89005924"}, {"rfc6238-sha1-2000000000", "69279037"}, {"rfc6238-sha1-20000000000", "65353130"},
                {"rfc6238-sha256-59", "46119246"}, {"rfc6238-sha256-1111111109", "68084774"}, {"rfc6238-sha256-1111111111", "67062674"},
                {"rfc6238-sha256-1234567890", "91819424"}, {"rfc6238-sha256-2000000000", "90698825"}, {"rfc6238-sha256-20000000000", "77737706"},
                {"rfc6238-sha512-59", "90693936"}, {"rfc6238-sha512-1111111109", "25091201"}, {"rfc6238-sha512-1111111111", "99943326"},
                {"rfc6238-sha512-1234567890", "93441116"}, {"rfc6238-sha512-2000000000", "38618901"}, {"rfc6238-sha512-20000000000", "47863826"},
        };
        for (String[] r : rfc) {
            assertEquals(r[1], Json.asObject(caseNamed(totp, "generate", r[0]).get("expected")).get("code"), r[0]);
        }

        Map<String, Object> jwks = doc("jwks.json");
        List<Object> rfcCases = Json.asList(Json.asObject(jwks.get("rfc")).get("cases"));
        assertEquals("NzbLsXh8uDCcd-6MNwXF4W_7noWXFZAfHkxZsRGC9Xs", Json.asObject(rfcCases.get(0)).get("thumbprint"));
        assertEquals("kPrK_qmxVWaYVA9wwBF6Iuo3vVzz7TxHCTwXBygrS4k", Json.asObject(rfcCases.get(1)).get("thumbprint"));
    }

    @Test
    @DisplayName("SSO-T-vectors-003 every vector file has positive and negative outcomes")
    void filesHaveBothOutcomes() {
        Map<String, Object> jwt = doc("jwt.json");
        long valid = Json.asList(jwt.get("cases")).stream().filter(c -> Boolean.TRUE.equals(Json.asObject(Json.asObject(c).get("expected")).get("valid"))).count();
        assertTrue(valid >= 5);
        assertTrue(Json.asList(jwt.get("cases")).size() - valid >= 20);
        Map<String, Object> web = doc("webauthn.json");
        long ok = Json.asList(web.get("cases")).stream().filter(c -> Boolean.TRUE.equals(Json.asObject(Json.asObject(c).get("expected")).get("ok"))).count();
        assertTrue(ok >= 8);
        assertTrue(Json.asList(web.get("cases")).size() - ok >= 15);
    }
}
