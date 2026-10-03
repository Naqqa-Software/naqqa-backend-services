package com.naqqa.analytics.collect;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.regex.Pattern;

public final class IpAnonymizer {

    private static final Pattern V4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern V6 = Pattern.compile("^[0-9a-fA-F:.]+$");

    private IpAnonymizer() {
    }

    public static InetAddress parse(String ip) {
        if (ip == null) {
            return null;
        }
        String v = ip.trim();
        int zone = v.indexOf('%');
        if (zone > 0) {
            v = v.substring(0, zone);
        }
        if (v.startsWith("[") && v.endsWith("]")) {
            v = v.substring(1, v.length() - 1);
        }
        boolean v4 = V4.matcher(v).matches();
        if (!v4 && !(v.contains(":") && V6.matcher(v).matches())) {
            return null;
        }
        try {
            return InetAddress.getByName(v);
        } catch (Exception e) {
            return null;
        }
    }

    public static String truncate(String ip) {
        InetAddress address = parse(ip);
        if (address == null) {
            return null;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            return (b[0] & 0xff) + "." + (b[1] & 0xff) + "." + (b[2] & 0xff) + ".0";
        }
        if (address instanceof Inet6Address) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i += 2) {
                if (i > 0) {
                    sb.append(':');
                }
                sb.append(Integer.toHexString(((b[i] & 0xff) << 8) | (b[i + 1] & 0xff)));
            }
            return sb.append("::").toString();
        }
        return null;
    }

    public static String clientIp(String remoteAddr, String forwardedFor, String realIp, boolean trustProxy) {
        if (trustProxy) {
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                String first = forwardedFor.split(",")[0].trim();
                if (parse(first) != null) {
                    return first;
                }
            }
            if (realIp != null && parse(realIp.trim()) != null) {
                return realIp.trim();
            }
        }
        return remoteAddr;
    }
}
