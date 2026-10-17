package com.naqqa.sso.tokens;

import com.naqqa.sso.SsoException;

import java.util.Arrays;

public final class IpAddresses {
    private IpAddresses() {
    }

    public static byte[] parse(String text) {
        if (text == null || text.isEmpty() || text.length() > 45) {
            throw new SsoException("ip.invalid");
        }
        byte[] out = text.indexOf(':') >= 0 ? parseV6(text) : parseV4(text);
        if (out == null) {
            throw new SsoException("ip.invalid");
        }
        return out;
    }

    public static byte[] unmapV4(byte[] addr) {
        if (addr.length == 16) {
            for (int i = 0; i < 10; i++) {
                if (addr[i] != 0) {
                    return addr;
                }
            }
            if (addr[10] == (byte) 0xFF && addr[11] == (byte) 0xFF) {
                return Arrays.copyOfRange(addr, 12, 16);
            }
        }
        return addr;
    }

    static byte[] parseV4(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3 || (p.length() > 1 && p.charAt(0) == '0')) {
                return null;
            }
            int v = 0;
            for (int j = 0; j < p.length(); j++) {
                char c = p.charAt(j);
                if (c < '0' || c > '9') {
                    return null;
                }
                v = v * 10 + (c - '0');
            }
            if (v > 255) {
                return null;
            }
            out[i] = (byte) v;
        }
        return out;
    }

    static byte[] parseV6(String s) {
        int dbl = s.indexOf("::");
        if (dbl >= 0 && s.indexOf("::", dbl + 1) >= 0) {
            return null;
        }
        byte[] v4Tail = null;
        String body = s;
        int lastColon = s.lastIndexOf(':');
        if (s.indexOf('.', lastColon) >= 0) {
            v4Tail = parseV4(s.substring(lastColon + 1));
            if (v4Tail == null) {
                return null;
            }
            body = s.substring(0, lastColon + 1);
            if (!body.endsWith("::")) {
                body = body.substring(0, body.length() - 1);
            }
        }
        int maxGroups = v4Tail == null ? 8 : 6;
        int[] head;
        int[] tail;
        if (dbl >= 0) {
            int split = body.indexOf("::");
            head = groups(body.substring(0, split));
            tail = groups(body.substring(split + 2));
            if (head == null || tail == null || head.length + tail.length >= maxGroups) {
                return null;
            }
        } else {
            head = groups(body);
            tail = new int[0];
            if (head == null || head.length != maxGroups) {
                return null;
            }
        }
        byte[] out = new byte[16];
        for (int i = 0; i < head.length; i++) {
            out[2 * i] = (byte) (head[i] >> 8);
            out[2 * i + 1] = (byte) head[i];
        }
        int tailStart = maxGroups - tail.length;
        for (int i = 0; i < tail.length; i++) {
            out[2 * (tailStart + i)] = (byte) (tail[i] >> 8);
            out[2 * (tailStart + i) + 1] = (byte) tail[i];
        }
        if (v4Tail != null) {
            System.arraycopy(v4Tail, 0, out, 12, 4);
        }
        return out;
    }

    private static int[] groups(String s) {
        if (s.isEmpty()) {
            return new int[0];
        }
        String[] parts = s.split(":", -1);
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 4) {
                return null;
            }
            int v = 0;
            for (int j = 0; j < p.length(); j++) {
                int d = Character.digit(p.charAt(j), 16);
                if (d < 0 || p.charAt(j) > 'f') {
                    return null;
                }
                v = (v << 4) | d;
            }
            out[i] = v;
        }
        return out;
    }
}
