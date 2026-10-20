package com.naqqa.acs;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcsRulesTest {

    @Test
    void parsesSensitiveAndSignedRules() {
        AcsRules rules = AcsRules.of(List.of("POST /api/auth/login login", "post\t/api/contact\tform", " "),
                List.of("GET /api/booklets/public/*/products", "* /api/export/**"), true);
        assertEquals(4, rules.size());
        assertTrue(rules.covers("POST", "/api/auth/login"));
        assertTrue(rules.covers("post", "/api/contact"));
        assertFalse(rules.covers("GET", "/api/auth/login"));
        assertTrue(rules.covers("GET", "/api/booklets/public/7/products"));
        assertFalse(rules.covers("GET", "/api/booklets/public/7/8/products"));
        assertTrue(rules.covers("DELETE", "/api/export/a/b"));
        assertEquals("login", rules.rules().get(0).action);
        assertTrue(rules.rules().get(2).signed);
    }

    @Test
    void signedRulesNeedTheSessionSecret() {
        AcsRules rules = AcsRules.of(List.of(), List.of("GET /api/promotions"), false);
        assertEquals(0, rules.size());
        assertFalse(rules.covers("GET", "/api/promotions"));
    }

    @Test
    void malformedRulesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> AcsRules.of(List.of("POST /api/x"), List.of(), false));
        assertThrows(IllegalArgumentException.class, () -> AcsRules.of(List.of(), List.of("GET /api/x extra"), true));
    }
}
