package com.naqqa.elasticsearch.common.unit;

import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

public class UnitParsersTest {

    @Test
    public void testTimeValueParsing() {
        Assert.assertEquals(1000L, TimeValue.parseTimeValue("1s", "t").millis());
        Assert.assertEquals(500L, TimeValue.parseTimeValue("500ms", "t").millis());
        Assert.assertEquals(60000L, TimeValue.parseTimeValue("1m", "t").millis());
        Assert.assertEquals(86400000L, TimeValue.parseTimeValue("1d", "t").millis());
        Assert.assertEquals(-1L, TimeValue.parseTimeValue("-1", "t").millis());
    }

    @Test
    public void testByteSizeValueParsing() {
        Assert.assertEquals(10L * 1024 * 1024 * 1024, ByteSizeValue.parseBytesSizeValue("10gb", "t").getBytes());
        Assert.assertEquals(2048L, ByteSizeValue.parseBytesSizeValue("2kb", "t").getBytes());
        Assert.assertEquals("1kb", ByteSizeValue.ofBytes(1024).toString());
    }

    @Test
    public void testRatioValueParsing() {
        RatioValue r1 = RatioValue.parseRatioValue("50%");
        Assert.assertEquals(50.0, r1.getAsPercent(), 0.0001);
        RatioValue r2 = RatioValue.parseRatioValue("0.5");
        Assert.assertEquals(50.0, r2.getAsPercent(), 0.0001);
        Assert.assertThrows(IllegalArgumentException.class, () -> RatioValue.parseRatioValue("150%"));
    }

    @Test
    public void testDistanceUnitParsing() {
        double meters = DistanceUnit.parse("10km", DistanceUnit.METERS, DistanceUnit.METERS);
        Assert.assertEquals(10000.0, meters, 0.0001);
        double miles = DistanceUnit.parse("5mi", DistanceUnit.METERS, DistanceUnit.MILES);
        Assert.assertEquals(5.0, miles, 0.0001);
    }

    @Test
    public void testFuzzinessAuto() {
        Fuzziness auto = Fuzziness.fromString("AUTO");
        Assert.assertTrue(auto.isAutomatic());
        Assert.assertEquals(0, auto.asDistance("ab"));
        Assert.assertEquals(1, auto.asDistance("abcd"));
        Assert.assertEquals(2, auto.asDistance("abcdefgh"));
        Fuzziness custom = Fuzziness.fromString("AUTO:4,8");
        Assert.assertTrue(custom.isAutomatic());
        Fuzziness fixed = Fuzziness.fromString("2");
        Assert.assertFalse(fixed.isAutomatic());
        Assert.assertEquals(2, fixed.asDistance("anything"));
    }
}
