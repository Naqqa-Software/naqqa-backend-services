package com.naqqa.elasticsearch.search.aggs.bucket.range;

import com.naqqa.elasticsearch.common.network.InetAddresses;

public record IpRangeSpec(String key, String cidr, String from, String to) {

    public boolean matches(String ip) {
        if (cidr != null) {
            return InetAddresses.isInRange(ip, cidr);
        }
        byte[] addr = InetAddresses.toBytes(InetAddresses.forString(ip));
        if (from != null) {
            byte[] fromBytes = InetAddresses.toBytes(InetAddresses.forString(from));
            if (compare(addr, fromBytes) < 0) {
                return false;
            }
        }
        if (to != null) {
            byte[] toBytes = InetAddresses.toBytes(InetAddresses.forString(to));
            if (compare(addr, toBytes) >= 0) {
                return false;
            }
        }
        return true;
    }

    private static int compare(byte[] a, byte[] b) {
        for (int i = 0; i < 16; i++) {
            int va = a[i] & 0xFF;
            int vb = b[i] & 0xFF;
            if (va != vb) {
                return va - vb;
            }
        }
        return 0;
    }

    public String effectiveKey() {
        if (key != null) {
            return key;
        }
        if (cidr != null) {
            return cidr;
        }
        return (from == null ? "*" : from) + "-" + (to == null ? "*" : to);
    }
}
