package com.naqqa.acs.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AcsPathsTest {

    @Test
    void matchesTheRoutingViewOfThePath() {
        assertEquals("/", AcsPaths.canonical(null));
        assertEquals("/api/promotions", AcsPaths.canonical("/api//promotions;x=1"));
        assertEquals("/api/booklets/public/7/products", AcsPaths.canonical("/api/booklets/public/%37/products"));
        assertEquals("/api/auth/login", AcsPaths.canonical("/api/x/../auth/./login"));
        assertEquals("/api/a%zz", AcsPaths.canonical("/api/a%zz"));
    }
}
