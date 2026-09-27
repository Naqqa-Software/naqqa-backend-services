package com.naqqa.elasticsearch.common.time;

import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.time.Instant;
import java.time.ZoneId;

public class DateTimeTest {

    @Test
    public void testStrictDateOptionalTime() {
        DateFormatter f = DateFormatter.forPattern("strict_date_optional_time");
        Instant withTime = f.parse("2020-01-01T12:30:45.123Z");
        Assert.assertEquals(1577881845123L, withTime.toEpochMilli());
        Instant dateOnly = f.parse("2020-01-01");
        Assert.assertEquals(1577836800000L, dateOnly.toEpochMilli());
    }

    @Test
    public void testEpochMillisAndSeconds() {
        DateFormatter millis = DateFormatter.forPattern("epoch_millis");
        Assert.assertEquals(1577836800000L, millis.parseMillis("1577836800000"));
        Assert.assertEquals("1577836800000", millis.formatMillis(1577836800000L));

        DateFormatter seconds = DateFormatter.forPattern("epoch_second");
        Assert.assertEquals(1577836800000L, seconds.parseMillis("1577836800"));
    }

    @Test
    public void testMultiFormatFallback() {
        DateFormatter multi = DateFormatter.forPattern("strict_date||epoch_millis");
        Assert.assertEquals(1577836800000L, multi.parseMillis("2020-01-01"));
        Assert.assertEquals(1577836800000L, multi.parseMillis("1577836800000"));
    }

    @Test
    public void testDateMathBasic() {
        DateFormatter f = DateFormatter.forPattern("strict_date_optional_time");
        DateMathParser math = new DateMathParser(f);
        long now = f.parseMillis("2020-01-01T00:00:00.000Z");
        long result = math.parse("now-1d/d", now, false, ZoneId.of("UTC"));
        Assert.assertEquals(f.parseMillis("2019-12-31T00:00:00.000Z"), result);
    }

    @Test
    public void testDateMathWithAnchorAndRoundUp() {
        DateFormatter f = DateFormatter.forPattern("strict_date_optional_time");
        DateMathParser math = new DateMathParser(f);
        long result = math.parse("2020-01-01||+1M/M", 0, false, ZoneId.of("UTC"));
        Assert.assertEquals(f.parseMillis("2020-02-01T00:00:00.000Z"), result);

        long roundUp = math.parse("2020-01-01||/M", 0, true, ZoneId.of("UTC"));
        Assert.assertEquals(f.parseMillis("2020-01-31T23:59:59.999Z"), roundUp);
    }
}
