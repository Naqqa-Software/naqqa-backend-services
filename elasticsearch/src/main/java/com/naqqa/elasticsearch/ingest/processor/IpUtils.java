package com.naqqa.elasticsearch.ingest.processor;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IpUtils {

    private IpUtils() {
    }

    private static final Map<String, List<String>> NAMED_RANGES = new LinkedHashMap<>();

    static {
        NAMED_RANGES.put("private", List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7", "fd00::/8"));
        NAMED_RANGES.put("loopback", List.of("127.0.0.0/8", "::1/128"));
        NAMED_RANGES.put("link-local", List.of("169.254.0.0/16", "fe80::/10"));
        NAMED_RANGES.put("unique-local", List.of("fc00::/7"));
        NAMED_RANGES.put("multicast", List.of("224.0.0.0/4", "ff00::/8"));
        NAMED_RANGES.put("unspecified", List.of("0.0.0.0/32", "::/128"));
        NAMED_RANGES.put("global-unicast", List.of("0.0.0.0/0", "::/0"));
    }

    public static boolean matchesAny(String ip, List<String> networks) {
        for (String network : networks) {
            if (matches(ip, network)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String ip, String network) {
        List<String> named = NAMED_RANGES.get(network);
        if (named != null) {
            return matchesAny(ip, named);
        }
        return cidrContains(ip, network);
    }

    public static boolean cidrContains(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            InetAddress network = InetAddress.getByName(parts[0]);
            int prefixLength = parts.length > 1 ? Integer.parseInt(parts[1]) : (network.getAddress().length * 8);
            InetAddress address = InetAddress.getByName(ip);
            byte[] netBytes = network.getAddress();
            byte[] addrBytes = address.getAddress();
            if (netBytes.length != addrBytes.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (netBytes[i] != addrBytes[i]) {
                    return false;
                }
            }
            if (remainingBits > 0) {
                int mask = 0xFF << (8 - remainingBits) & 0xFF;
                if ((netBytes[fullBytes] & mask) != (addrBytes[fullBytes] & mask)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
