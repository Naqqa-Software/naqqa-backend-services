package com.naqqa.elasticsearch.common.network;

import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.net.InetAddress;

public class InetAddressesTest {

    @Test
    public void testIsInetAddress() {
        Assert.assertTrue(InetAddresses.isInetAddress("192.168.1.1"));
        Assert.assertTrue(InetAddresses.isInetAddress("::1"));
        Assert.assertFalse(InetAddresses.isInetAddress("not-an-ip"));
    }

    @Test
    public void testCidrMatching() {
        Assert.assertTrue(InetAddresses.isInRange("192.168.1.42", "192.168.1.0/24"));
        Assert.assertFalse(InetAddresses.isInRange("192.168.2.42", "192.168.1.0/24"));
    }

    @Test
    public void testSortableEncodingRoundTrip() {
        InetAddress addr = InetAddresses.forString("10.0.0.5");
        byte[] encoded = InetAddresses.toBytes(addr);
        Assert.assertEquals(16, encoded.length);
        InetAddress decoded = InetAddresses.fromSortableBytes(encoded);
        Assert.assertEquals(addr.getHostAddress(), decoded.getHostAddress());
    }
}
