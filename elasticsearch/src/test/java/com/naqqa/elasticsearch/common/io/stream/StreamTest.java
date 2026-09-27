package com.naqqa.elasticsearch.common.io.stream;

import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class StreamTest {

    @Test
    public void testVIntAndVLongRoundTrip() throws IOException {
        BytesStreamOutput out = new BytesStreamOutput();
        out.writeVInt(0);
        out.writeVInt(127);
        out.writeVInt(128);
        out.writeVInt(Integer.MAX_VALUE);
        out.writeVLong(0L);
        out.writeVLong(Long.MAX_VALUE);

        ByteBufferStreamInput in = new ByteBufferStreamInput(out.toByteArray());
        Assert.assertEquals(0, in.readVInt());
        Assert.assertEquals(127, in.readVInt());
        Assert.assertEquals(128, in.readVInt());
        Assert.assertEquals(Integer.MAX_VALUE, in.readVInt());
        Assert.assertEquals(0L, in.readVLong());
        Assert.assertEquals(Long.MAX_VALUE, in.readVLong());
    }

    @Test
    public void testZigZagLongNegatives() throws IOException {
        BytesStreamOutput out = new BytesStreamOutput();
        out.writeZLong(-1L);
        out.writeZLong(Long.MIN_VALUE);
        out.writeZLong(12345L);

        ByteBufferStreamInput in = new ByteBufferStreamInput(out.toByteArray());
        Assert.assertEquals(-1L, in.readZLong());
        Assert.assertEquals(Long.MIN_VALUE, in.readZLong());
        Assert.assertEquals(12345L, in.readZLong());
    }

    @Test
    public void testStringAndCollections() throws IOException {
        BytesStreamOutput out = new BytesStreamOutput();
        out.writeString("hello éè world");
        out.writeOptionalString(null);
        out.writeStringCollection(List.of("a", "b", "c"));

        ByteBufferStreamInput in = new ByteBufferStreamInput(out.toByteArray());
        Assert.assertEquals("hello éè world", in.readString());
        Assert.assertNull(in.readOptionalString());
        Assert.assertEquals(List.of("a", "b", "c"), in.readStringList());
    }

    @Test
    public void testGenericValueMapAndList() throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("a", 1);
        map.put("b", List.of("x", "y"));
        map.put("c", null);

        BytesStreamOutput out = new BytesStreamOutput();
        out.writeGenericValue(map);

        ByteBufferStreamInput in = new ByteBufferStreamInput(out.toByteArray());
        Object read = in.readGenericValue();
        Assert.assertEquals(map, read);
    }
}
