package com.naqqa.sso.oauth;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

public final class IpGuard {
    private IpGuard() {
    }

    public static boolean isBlocked(InetAddress address) {
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address || b.length == 4) {
            return blockedV4(b);
        }
        if (address instanceof Inet6Address && b.length == 16) {
            return blockedV6(b);
        }
        return true;
    }

    static boolean blockedV4(byte[] b) {
        int a = b[0] & 0xff;
        int c = b[1] & 0xff;
        int d = b[2] & 0xff;
        if (a == 0 || a == 10 || a == 127) {
            return true;
        }
        if (a == 100 && c >= 64 && c <= 127) {
            return true;
        }
        if (a == 169 && c == 254) {
            return true;
        }
        if (a == 172 && c >= 16 && c <= 31) {
            return true;
        }
        if (a == 192 && c == 168) {
            return true;
        }
        if (a == 192 && c == 0 && (d == 0 || d == 2)) {
            return true;
        }
        if (a == 198 && (c == 18 || c == 19)) {
            return true;
        }
        if (a == 198 && c == 51 && d == 100) {
            return true;
        }
        if (a == 203 && c == 0 && d == 113) {
            return true;
        }
        return a >= 224;
    }

    static boolean blockedV6(byte[] b) {
        boolean zeroPrefix80 = true;
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                zeroPrefix80 = false;
                break;
            }
        }
        if (zeroPrefix80) {
            return true;
        }
        int first = b[0] & 0xff;
        int second = b[1] & 0xff;
        if ((first & 0xe0) != 0x20) {
            return true;
        }
        if (first == 0x20 && second == 0x02) {
            return blockedV4(new byte[] {b[2], b[3], b[4], b[5]});
        }
        if (first == 0x20 && second == 0x01) {
            int third = b[2] & 0xff;
            int fourth = b[3] & 0xff;
            if (third == 0 && (fourth == 0 || fourth == 2)) {
                return true;
            }
            if (third == 0x0d && fourth == 0xb8) {
                return true;
            }
        }
        return false;
    }
}
