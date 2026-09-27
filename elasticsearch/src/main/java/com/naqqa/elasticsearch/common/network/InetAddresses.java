package com.naqqa.elasticsearch.common.network;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

public final class InetAddresses {

    private InetAddresses() {
    }

    public static boolean isInetAddress(String ipString) {
        return ipStringToBytes(ipString) != null;
    }

    private static byte[] ipStringToBytes(String ipString) {
        boolean hasColon = ipString.indexOf(':') != -1;
        boolean hasDot = ipString.indexOf('.') != -1;
        if (hasColon) {
            return textToNumericFormatV6(ipString);
        } else if (hasDot) {
            return textToNumericFormatV4(ipString);
        }
        return null;
    }

    private static byte[] textToNumericFormatV4(String ip) {
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            try {
                int val = Integer.parseInt(parts[i]);
                if (val < 0 || val > 255 || parts[i].isEmpty()) {
                    return null;
                }
                bytes[i] = (byte) val;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return bytes;
    }

    private static byte[] textToNumericFormatV6(String ip) {
        try {
            InetAddress addr = InetAddress.getByName(ip);
            byte[] addrBytes = addr.getAddress();
            if (addrBytes.length == 16) {
                return addrBytes;
            }
            if (addrBytes.length == 4) {
                return mapToV6(addrBytes);
            }
            return null;
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static byte[] mapToV6(byte[] v4) {
        byte[] v6 = new byte[16];
        v6[10] = (byte) 0xff;
        v6[11] = (byte) 0xff;
        System.arraycopy(v4, 0, v6, 12, 4);
        return v6;
    }

    public static InetAddress forString(String ipString) {
        byte[] bytes = ipStringToBytes(ipString);
        if (bytes == null) {
            throw new IllegalArgumentException("'" + ipString + "' is not an IP string literal.");
        }
        try {
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static String toAddrString(InetAddress addr) {
        return addr.getHostAddress();
    }

    public static byte[] toBytes(InetAddress addr) {
        byte[] a = addr.getAddress();
        if (a.length == 4) {
            return mapToV6(a);
        }
        return a;
    }

    public static InetAddress fromSortableBytes(byte[] value) {
        try {
            if (isMappedV4(value)) {
                byte[] v4 = Arrays.copyOfRange(value, 12, 16);
                return InetAddress.getByAddress(v4);
            }
            return InetAddress.getByAddress(value);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static boolean isMappedV4(byte[] v6) {
        for (int i = 0; i < 10; i++) {
            if (v6[i] != 0) {
                return false;
            }
        }
        return (v6[10] & 0xFF) == 0xFF && (v6[11] & 0xFF) == 0xFF;
    }

    public static boolean isInRange(String ipAddress, String cidr) {
        String[] parts = cidr.split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid CIDR notation [" + cidr + "]");
        }
        InetAddress network = forString(parts[0]);
        int prefixLength = Integer.parseInt(parts[1]);
        InetAddress addr = forString(ipAddress);
        byte[] networkBytes = toBytes(network);
        byte[] addrBytes = toBytes(addr);
        int bits = network.getAddress().length == 4 ? 96 + prefixLength : prefixLength;
        return maskedEquals(addrBytes, networkBytes, bits);
    }

    public static boolean isInRangeStrict(byte[] addrBytes16, byte[] networkBytes16, int prefixLength) {
        return maskedEquals(addrBytes16, networkBytes16, prefixLength);
    }

    private static boolean maskedEquals(byte[] a, byte[] b, int prefixBits) {
        int fullBytes = prefixBits / 8;
        int remBits = prefixBits % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        if (remBits > 0 && fullBytes < a.length) {
            int mask = 0xFF << (8 - remBits) & 0xFF;
            if ((a[fullBytes] & mask) != (b[fullBytes] & mask)) {
                return false;
            }
        }
        return true;
    }
}
