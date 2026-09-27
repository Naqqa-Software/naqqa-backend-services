package com.naqqa.elasticsearch.http;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class UrlCodec {

    private UrlCodec() {
    }

    public static String decode(String value, boolean plusAsSpace) {
        if (value.indexOf('%') < 0 && (!plusAsSpace || value.indexOf('+') < 0)) {
            return value;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length());
        int i = 0;
        int len = value.length();
        while (i < len) {
            char c = value.charAt(i);
            if (c == '%') {
                if (i + 2 >= len) {
                    throw new IllegalArgumentException("invalid percent-encoding in [" + value + "]");
                }
                int hi = hexDigit(value.charAt(i + 1));
                int lo = hexDigit(value.charAt(i + 2));
                out.write((hi << 4) | lo);
                i += 3;
            } else if (c == '+' && plusAsSpace) {
                out.write(' ');
                i++;
            } else {
                if (c < 0x80) {
                    out.write(c);
                    i++;
                } else {
                    byte[] utf8 = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                    out.write(utf8, 0, utf8.length);
                    i++;
                }
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int hexDigit(char c) {
        int digit = Character.digit(c, 16);
        if (digit < 0) {
            throw new IllegalArgumentException("invalid hex digit: " + c);
        }
        return digit;
    }

    public static String encodePathSegment(String value) {
        StringBuilder sb = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }
}
