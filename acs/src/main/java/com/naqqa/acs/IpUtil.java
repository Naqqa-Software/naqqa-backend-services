package com.naqqa.acs;

import java.util.List;
import java.util.regex.Pattern;

public final class IpUtil {
    private IpUtil() {}

    public static final class Ip {
        public final int v;
        public final byte[] bytes;

        Ip(int v, byte[] bytes) {
            this.v = v;
            this.bytes = bytes;
        }
    }

    private static final Pattern DEC = Pattern.compile("^\\d{1,3}$");
    private static final Pattern HEX4 = Pattern.compile("^[0-9a-fA-F]{1,4}$");
    private static final Pattern V6CHARS = Pattern.compile("^[0-9a-fA-F:.]+$");

    public static Ip parse(String input) {
        if (input == null || input.isEmpty() || input.length() > 64) return null;
        String s = input.trim();
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
        int zone = s.indexOf('%');
        if (zone >= 0) s = s.substring(0, zone);
        byte[] v4 = parseV4(s);
        if (v4 != null) return new Ip(4, v4);
        byte[] v6 = parseV6(s);
        if (v6 == null) return null;
        boolean mapped = true;
        for (int i = 0; i < 10; i++) if (v6[i] != 0) mapped = false;
        if (mapped && (v6[10] & 0xff) == 0xff && (v6[11] & 0xff) == 0xff) {
            byte[] b = new byte[4];
            System.arraycopy(v6, 12, b, 0, 4);
            return new Ip(4, b);
        }
        return new Ip(6, v6);
    }

    private static byte[] parseV4(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (!DEC.matcher(p).matches()) return null;
            if (p.length() > 1 && p.startsWith("0")) return null;
            int n = Integer.parseInt(p);
            if (n > 255) return null;
            out[i] = (byte) n;
        }
        return out;
    }

    private static boolean pushGroup(String[] arr, List<Integer> target) {
        for (int i = 0; i < arr.length; i++) {
            String g = arr[i];
            if (g.contains(".")) {
                if (i != arr.length - 1) return false;
                byte[] v4 = parseV4(g);
                if (v4 == null) return false;
                target.add(((v4[0] & 0xff) << 8) | (v4[1] & 0xff));
                target.add(((v4[2] & 0xff) << 8) | (v4[3] & 0xff));
                continue;
            }
            if (!HEX4.matcher(g).matches()) return false;
            target.add(Integer.parseInt(g, 16));
        }
        return true;
    }

    private static byte[] parseV6(String s) {
        if (!V6CHARS.matcher(s).matches()) return null;
        int dbl = s.indexOf("::");
        if (dbl != s.lastIndexOf("::")) return null;
        String[] head;
        String[] tail = new String[0];
        if (dbl >= 0) {
            String h = s.substring(0, dbl);
            String t = s.substring(dbl + 2);
            head = h.isEmpty() ? new String[0] : h.split(":", -1);
            tail = t.isEmpty() ? new String[0] : t.split(":", -1);
        } else {
            head = s.split(":", -1);
        }
        List<Integer> h = new java.util.ArrayList<>();
        List<Integer> t = new java.util.ArrayList<>();
        if (!pushGroup(head, h) || !pushGroup(tail, t)) return null;
        int[] words = new int[8];
        if (dbl >= 0) {
            if (h.size() + t.size() > 7) return null;
            int k = 0;
            for (int x : h) words[k++] = x;
            k = 8 - t.size();
            for (int x : t) words[k++] = x;
        } else {
            if (h.size() != 8) return null;
            for (int i = 0; i < 8; i++) words[i] = h.get(i);
        }
        byte[] out = new byte[16];
        for (int i = 0; i < 8; i++) {
            out[i * 2] = (byte) ((words[i] >> 8) & 255);
            out[i * 2 + 1] = (byte) (words[i] & 255);
        }
        return out;
    }

    public static String ipClass(String ip, int v4Prefix, int v6Prefix) {
        Ip a = parse(ip);
        if (a == null) return null;
        int prefix = a.v == 4 ? v4Prefix : v6Prefix;
        byte[] out = a.bytes.clone();
        int bits = prefix;
        for (int i = 0; i < out.length; i++) {
            int take = Math.max(0, Math.min(8, bits));
            out[i] = (byte) ((out[i] & 0xff) & ((0xff << (8 - take)) & 0xff));
            bits -= 8;
        }
        StringBuilder sb = new StringBuilder();
        if (a.v == 4) {
            sb.append("4:");
            for (int i = 0; i < 4; i++) {
                if (i > 0) sb.append('.');
                sb.append(out[i] & 0xff);
            }
        } else {
            sb.append("6:");
            for (int i = 0; i < 16; i += 2) {
                if (i > 0) sb.append(':');
                sb.append(Integer.toHexString(((out[i] & 0xff) << 8) | (out[i + 1] & 0xff)));
            }
        }
        return sb.append('/').append(prefix).toString();
    }

    public static String ipClass(String ip) {
        return ipClass(ip, 24, 64);
    }

    public static String format(String ip) {
        Ip a = parse(ip);
        if (a == null) return null;
        if (a.v == 4) return (a.bytes[0] & 0xff) + "." + (a.bytes[1] & 0xff) + "." + (a.bytes[2] & 0xff) + "." + (a.bytes[3] & 0xff);
        int[] w = new int[8];
        for (int i = 0; i < 8; i++) w[i] = ((a.bytes[i * 2] & 0xff) << 8) | (a.bytes[i * 2 + 1] & 0xff);
        int bestStart = -1, bestLen = 0;
        for (int i = 0; i < 8;) {
            if (w[i] != 0) {
                i++;
                continue;
            }
            int j = i;
            while (j < 8 && w[j] == 0) j++;
            if (j - i > bestLen) {
                bestStart = i;
                bestLen = j - i;
            }
            i = j;
        }
        StringBuilder sb = new StringBuilder();
        if (bestLen < 2) {
            for (int i = 0; i < 8; i++) {
                if (i > 0) sb.append(':');
                sb.append(Integer.toHexString(w[i]));
            }
            return sb.toString();
        }
        for (int i = 0; i < bestStart; i++) {
            if (i > 0) sb.append(':');
            sb.append(Integer.toHexString(w[i]));
        }
        sb.append("::");
        for (int i = bestStart + bestLen; i < 8; i++) {
            if (i > bestStart + bestLen) sb.append(':');
            sb.append(Integer.toHexString(w[i]));
        }
        return sb.toString();
    }

    public static boolean inCidr(String ip, String cidr) {
        Ip a = parse(ip);
        if (a == null || cidr == null) return false;
        int slash = cidr.indexOf('/');
        Ip c = parse(slash >= 0 ? cidr.substring(0, slash) : cidr);
        if (c == null || a.v != c.v) return false;
        int max = c.v == 4 ? 32 : 128;
        int prefix = max;
        if (slash >= 0) {
            String p = cidr.substring(slash + 1);
            if (!DEC.matcher(p).matches()) return false;
            prefix = Integer.parseInt(p);
            if (prefix > max) return false;
        }
        int bits = prefix;
        for (int i = 0; i < a.bytes.length && bits > 0; i++) {
            int take = Math.min(8, bits);
            int mask = (0xff << (8 - take)) & 0xff;
            if (((a.bytes[i] & 0xff) & mask) != ((c.bytes[i] & 0xff) & mask)) return false;
            bits -= take;
        }
        return true;
    }

    public static boolean inAny(String ip, List<String> cidrs) {
        for (String c : cidrs) if (inCidr(ip, c)) return true;
        return false;
    }

    public static String resolveClientIp(String remoteAddr, String xff, List<String> trustedProxies) {
        String peer = format(remoteAddr);
        if (peer == null) peer = "0.0.0.0";
        if (xff == null || xff.isEmpty() || trustedProxies.isEmpty() || !inAny(peer, trustedProxies)) return peer;
        List<String> hops = new java.util.ArrayList<>();
        for (String s : xff.split(",", -1)) {
            String t = s.trim();
            if (!t.isEmpty()) hops.add(t);
        }
        if (hops.size() > 20) hops = hops.subList(hops.size() - 20, hops.size());
        for (int i = hops.size() - 1; i >= 0; i--) {
            String ip = format(hops.get(i));
            if (ip == null) return peer;
            if (!inAny(ip, trustedProxies)) return ip;
        }
        return peer;
    }
}
