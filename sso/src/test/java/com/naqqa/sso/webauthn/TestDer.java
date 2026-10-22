package com.naqqa.sso.webauthn;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

final class TestDer {
    private TestDer() {
    }

    static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (tag > 0xFF) {
            out.write((tag >>> 16) & 0xFF);
            out.write((tag >>> 8) & 0xFF);
            out.write(tag & 0xFF);
        } else {
            out.write(tag);
        }
        int len = value.length;
        if (len < 0x80) {
            out.write(len);
        } else if (len < 0x100) {
            out.write(0x81);
            out.write(len);
        } else {
            out.write(0x82);
            out.write(len >>> 8);
            out.write(len & 0xFF);
        }
        out.writeBytes(value);
        return out.toByteArray();
    }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    static byte[] seq(byte[]... parts) {
        return tlv(0x30, cat(parts));
    }

    static byte[] set(byte[]... parts) {
        return tlv(0x31, cat(parts));
    }

    static byte[] integer(long v) {
        return tlv(0x02, BigInteger.valueOf(v).toByteArray());
    }

    static byte[] enumerated(long v) {
        return tlv(0x0A, BigInteger.valueOf(v).toByteArray());
    }

    static byte[] octet(byte[] v) {
        return tlv(0x04, v);
    }

    static byte[] bool(boolean v) {
        return tlv(0x01, new byte[]{(byte) (v ? 0xFF : 0)});
    }

    static byte[] nul() {
        return new byte[]{0x05, 0x00};
    }

    static byte[] utf8(String s) {
        return tlv(0x0C, s.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] printable(String s) {
        return tlv(0x13, s.getBytes(StandardCharsets.US_ASCII));
    }

    static byte[] bitString(byte[] v) {
        return tlv(0x03, cat(new byte[]{0}, v));
    }

    static byte[] explicit(int n, byte[] content) {
        return tlv(0xA0 | n, content);
    }

    static byte[] utcTime(ZonedDateTime t) {
        String s = t.withZoneSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyMMddHHmmss")) + "Z";
        return tlv(0x17, s.getBytes(StandardCharsets.US_ASCII));
    }

    static byte[] oid(String dotted) {
        String[] p = dotted.split("\\.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(Integer.parseInt(p[0]) * 40 + Integer.parseInt(p[1]));
        for (int i = 2; i < p.length; i++) {
            long v = Long.parseLong(p[i]);
            byte[] tmp = new byte[10];
            int n = 0;
            do {
                tmp[n++] = (byte) (v & 0x7F);
                v >>>= 7;
            } while (v != 0);
            for (int j = n - 1; j >= 0; j--) {
                out.write(tmp[j] | (j > 0 ? 0x80 : 0));
            }
        }
        return tlv(0x06, out.toByteArray());
    }
}
