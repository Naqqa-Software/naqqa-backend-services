package com.naqqa.elasticsearch.common.json;

import com.naqqa.elasticsearch.common.xcontent.XContentLocation;
import com.naqqa.elasticsearch.common.xcontent.XContentParseException;
import com.naqqa.elasticsearch.common.xcontent.XContentParser;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

public class JsonRoundTripTest {

    @Test
    public void testBasicObjectRoundTrip() {
        String json = "{\"a\":1,\"b\":\"hello\",\"c\":true,\"d\":null,\"e\":[1,2,3]}";
        JsonParser parser = new JsonParser(json);
        parser.nextToken();
        Object value = parser.readValue();
        Map<?, ?> map = (Map<?, ?>) value;
        Assert.assertEquals(1, map.get("a"));
        Assert.assertEquals("hello", map.get("b"));
        Assert.assertEquals(true, map.get("c"));
        Assert.assertNull(map.get("d"));
        Assert.assertEquals(List.of(1, 2, 3), map.get("e"));
        String written = JsonWriter.toJson(map, false);
        JsonParser reparsed = new JsonParser(written);
        reparsed.nextToken();
        Assert.assertEquals(map, reparsed.readValue());
    }

    @Test
    public void testUnicodeEscapesAndSurrogatePairs() {
        String json = "{\"s\":\"caf\\u00e9 \\ud83d\\ude00\"}";
        JsonParser parser = new JsonParser(json);
        parser.nextToken();
        Map<?, ?> map = (Map<?, ?>) parser.readValue();
        String s = (String) map.get("s");
        Assert.assertEquals("café 😀", s);
        Assert.assertEquals(7, s.length());
    }

    @Test
    public void testBigNumbers() {
        String json = "{\"big\":123456789012345678901234567890,\"dec\":1.7976931348623157E400}";
        JsonParser parser = new JsonParser(json);
        parser.nextToken();
        parser.nextToken();
        parser.nextToken();
        Assert.assertEquals(XContentParser.NumberType.BIG_INTEGER, parser.numberType());
        Assert.assertEquals(new BigInteger("123456789012345678901234567890"), parser.numberValue());
        parser.nextToken();
        parser.nextToken();
        Assert.assertEquals(XContentParser.NumberType.BIG_DECIMAL, parser.numberType());
        Assert.assertTrue(parser.numberValue() instanceof BigDecimal);
    }

    @Test
    public void testErrorPositionsReported() {
        String json = "{\n  \"a\": tru\n}";
        JsonParser parser = new JsonParser(json);
        XContentParseException ex = Assert.assertThrows(XContentParseException.class, () -> {
            parser.nextToken();
            parser.nextToken();
            parser.nextToken();
        });
        XContentLocation loc = ex.getLocation();
        Assert.assertEquals(2, loc.lineNumber());
    }

    @Test
    public void testJsonValueTreeAndNdJson() {
        JsonObject obj = new JsonObject().put("name", "es").put("count", 3);
        JsonArray arr = new JsonArray().add("x").add("y");
        obj.put("tags", arr);
        Assert.assertEquals("es", obj.getString("name"));
        Assert.assertEquals(3, obj.getInt("count", -1));
        Assert.assertEquals(2, obj.getArray("tags").size());

        List<Object> docs = List.of(Map.of("index", Map.of("_id", "1")), Map.of("field", "value"));
        String nd = NdJson.write(docs);
        List<Object> parsedBack = NdJson.readAll(nd);
        Assert.assertEquals(docs, parsedBack);
    }
}
