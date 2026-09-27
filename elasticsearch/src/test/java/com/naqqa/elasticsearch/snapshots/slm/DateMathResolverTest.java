package com.naqqa.elasticsearch.snapshots.slm;

import com.naqqa.elasticsearch.test.Test;

import java.time.Instant;
import java.time.ZoneOffset;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;

public final class DateMathResolverTest {

    @Test
    public void plainPatternWithoutDateMathIsUnchanged() {
        assertEquals("plain-name", DateMathResolver.resolve("plain-name", Instant.now(), ZoneOffset.UTC));
    }

    @Test
    public void resolvesNowWithDefaultDayFormat() {
        Instant now = Instant.parse("2026-03-15T10:20:30Z");
        String result = DateMathResolver.resolve("<snap-{now/d}>", now, ZoneOffset.UTC);
        assertEquals("snap-2026.03.15", result);
    }

    @Test
    public void resolvesArithmeticAndCustomFormat() {
        Instant now = Instant.parse("2026-03-15T10:20:30Z");
        String result = DateMathResolver.resolve("<snap-{now-1d{yyyy-MM-dd}}>", now, ZoneOffset.UTC);
        assertEquals("snap-2026-03-14", result);
    }
}
