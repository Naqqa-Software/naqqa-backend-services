package com.naqqa.auth.config;

import com.naqqa.auth.config.authorities.CompactAuthorities;
import com.naqqa.auth.config.authorities.JwtToAuthorities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PERF-FE-1: the compact authority claim is lossless and refuses anything it did not produce. */
public class CompactAuthoritiesTest {

    /** The authority set of the ADMIN role of naqqa-os (every authority, LARGE audit database, 154 names). */
    public static final List<String> OS_ADMIN = List.of(("SSO_ADMIN_CLIENTS SSO_ADMIN_KEYS SSO_ADMIN_TENANTS SSO_ADMIN_TOKENS SSO_ADMIN_USERS "
            + "SSO_API_TOKENS SSO_AUDIT SSO_AUDIT_EXPORT SSO_IMPERSONATE").split(" "));

    public static List<String> osAdmin() {
        List<String> out = new ArrayList<>(OS_ADMIN);
        Map<String, String> families = Map.ofEntries(
                Map.entry("ai", "builder create delete entity flow offer read task techdesc time update"),
                Map.entry("analytics", "create delete read update"), Map.entry("builder", "create delete publish read update"),
                Map.entry("candidate", "create delete read update"), Map.entry("client", "create delete read update"),
                Map.entry("comment", "create delete read reply update"), Map.entry("config", "create delete read update"),
                Map.entry("crunch", "create delete read update"), Map.entry("design", "create delete read update"),
                Map.entry("entity", "create delete generate read update"), Map.entry("itcompany", "create delete read update"),
                Map.entry("kanban", "create delete read update"), Map.entry("log", "create delete read update"),
                Map.entry("moldova", "create delete manage read update"),
                Map.entry("offer", "comment create delete generate read status update"),
                Map.entry("outreach", "create delete manage read update"), Map.entry("partners", "create delete manage read update"),
                Map.entry("project", "create delete read switch update"), Map.entry("prospect", "create delete manage read update"),
                Map.entry("roles", "create delete read update"), Map.entry("seofarm", "create delete generate manage read update"),
                Map.entry("shield", "create delete evidence:export manage read update"), Map.entry("sources", "create delete read update"),
                Map.entry("sub_roles", "create delete read update"), Map.entry("task", "assign create delete generate read update"),
                Map.entry("techdesc", "create delete generate read update"), Map.entry("tender", "create delete read update"),
                Map.entry("time", "create delete generate read report update"),
                Map.entry("users", "create delete disable enable read update"));
        families.forEach((k, v) -> {
            for (String a : v.split(" ")) {
                out.add(k + ":" + a);
            }
        });
        return out;
    }

    private static Set<String> names(Map<String, Object> claims) {
        return JwtToAuthorities.fromClaims(claims).stream().map(GrantedAuthority::getAuthority)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    @DisplayName("SSO-T-auth-801 the os ADMIN set round-trips through authz, shrinks below a third and small role sets stay inline")
    void roundTrip() {
        List<String> admin = osAdmin();
        assertEquals(154, admin.size());
        assertTrue(CompactAuthorities.shouldCompact(admin));
        String encoded = CompactAuthorities.encode(admin);
        int plain = admin.stream().mapToInt(s -> s.length() + 3).sum();
        assertTrue(encoded.length() * 3 < plain, encoded.length() + " vs " + plain);
        assertEquals(new TreeSet<>(admin), names(Map.of(CompactAuthorities.CLAIM, encoded)));
        // Every default role of naqqa-os other than ADMIN keeps the plain list (largest: DESIGNER, 22 names).
        assertFalse(CompactAuthorities.shouldCompact(admin.subList(0, 30)));
        assertFalse(CompactAuthorities.shouldCompact(List.of()));
        assertFalse(CompactAuthorities.shouldCompact(List.of("a\nb")));
    }

    @Test
    @DisplayName("SSO-T-auth-802 random subsets of 0 to 600 authorities decode to exactly the encoded set")
    void randomSets() {
        Random random = new Random(42);
        List<String> catalog = new ArrayList<>(osAdmin());
        for (int i = 0; catalog.size() < 600; i++) {
            catalog.add("extra" + (i % 37) + ":" + "action-" + i);
        }
        for (int round = 0; round < 300; round++) {
            Set<String> set = new TreeSet<>();
            int n = random.nextInt(catalog.size() + 1);
            for (int i = 0; i < n; i++) {
                set.add(catalog.get(random.nextInt(catalog.size())));
            }
            if (set.isEmpty()) {
                continue;
            }
            assertEquals(set, names(Map.of(CompactAuthorities.CLAIM, CompactAuthorities.encode(set))));
        }
    }

    private static String deflate(byte[] raw) {
        Deflater d = new Deflater(9, true);
        d.setInput(raw);
        d.finish();
        byte[] buf = new byte[raw.length + 64];
        int n = d.deflate(buf);
        d.end();
        return CompactAuthorities.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(buf, n));
    }

    @Test
    @DisplayName("SSO-T-auth-803 malformed, truncated, oversized or non-string authz values are refused, never read as no authorities")
    void malformed() {
        String good = CompactAuthorities.encode(osAdmin());
        for (Object bad : List.of("", "d1.", "d2." + good.substring(3), "d1.!!!!", good.substring(0, good.length() - 6), good + "AAAA",
                42, List.of("a"), deflate("a\n\nb".getBytes(StandardCharsets.UTF_8)),
                deflate(new byte[200_000]))) {
            assertThrows(IllegalArgumentException.class, () -> JwtToAuthorities.fromClaims(Map.of(CompactAuthorities.CLAIM, bad)),
                    String.valueOf(bad));
        }
        // the plain list is read as before
        assertEquals(Set.of("a:b"), names(Map.of(JwtToAuthorities.AUTHORITY_CLAIM_NAME, List.of("a:b", 7))));
    }

    @Test
    @DisplayName("SSO-T-auth-807 AU-BE-66: bombs and oversized values stop before inflating, duplicates, unsorted, control characters, both claims and other versions are refused")
    void attacks() {
        // A 5 MB zero bomb fits the encoded cap (about 5 KB) and stops at the 64 KB inflate cap, quickly.
        String bomb = deflate(new byte[5_000_000]);
        assertTrue(bomb.length() < CompactAuthorities.MAX_ENCODED, String.valueOf(bomb.length()));
        long t0 = System.nanoTime();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> CompactAuthorities.decode(bomb));
        assertTrue(e.getMessage().contains("too large"), e.getMessage());
        assertTrue(System.nanoTime() - t0 < 500_000_000L, "bomb not stopped early");
        // A bigger value is refused on its length alone, before base64 or inflate run.
        String huge = CompactAuthorities.PREFIX + "A".repeat(CompactAuthorities.MAX_ENCODED);
        e = assertThrows(IllegalArgumentException.class, () -> CompactAuthorities.decode(huge));
        assertEquals("authz claim too large", e.getMessage());
        // Too many entries.
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            many.append(i == 0 ? "" : "\n").append(String.format("a%05d", i));
        }
        assertThrows(IllegalArgumentException.class, () -> CompactAuthorities.decode(deflate(many.toString().getBytes(StandardCharsets.UTF_8))));
        // Non canonical bodies a foreign encoder could write: duplicates, unsorted, control characters, overlong names.
        for (String body : List.of("a:read\na:read", "b:read\na:read", "a:read\nb\tread", "a:read\nb\u0000", "a:read\n" + "x".repeat(300),
                "a:read\n", "\na:read")) {
            assertThrows(IllegalArgumentException.class, () -> CompactAuthorities.decode(deflate(body.getBytes(StandardCharsets.UTF_8))),
                    body);
        }
        // The canonical body of the same names is accepted (control for the cases above).
        assertEquals(List.of("a:read", "b:read"), CompactAuthorities.decode(deflate("a:read\nb:read".getBytes(StandardCharsets.UTF_8))));
        // Version confusion: only d1. is known; a zlib (not raw) stream is refused.
        String good = CompactAuthorities.encode(osAdmin());
        for (String v : List.of("d0.", "D1.", "d1..", "z1.")) {
            assertThrows(IllegalArgumentException.class, () -> CompactAuthorities.decode(v + good.substring(3)), v);
        }
        Deflater zlib = new Deflater(9, false);
        zlib.setInput("a:read".getBytes(StandardCharsets.UTF_8));
        zlib.finish();
        byte[] buf = new byte[64];
        int n = zlib.deflate(buf);
        zlib.end();
        assertThrows(IllegalArgumentException.class, () -> CompactAuthorities.decode(CompactAuthorities.PREFIX
                + Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(buf, n))));
        // Both forms in one token: refused, never merged.
        assertThrows(IllegalArgumentException.class, () -> JwtToAuthorities.fromClaims(Map.of(
                CompactAuthorities.CLAIM, good, JwtToAuthorities.AUTHORITY_CLAIM_NAME, List.of("extra:grant"))));
        // Names the compact form cannot carry canonically fall back to the plain list.
        List<String> withControl = new ArrayList<>(osAdmin());
        withControl.add("bad\tname");
        assertFalse(CompactAuthorities.shouldCompact(withControl));
        List<String> withLong = new ArrayList<>(osAdmin());
        withLong.add("y".repeat(300));
        assertFalse(CompactAuthorities.shouldCompact(withLong));
    }
}
