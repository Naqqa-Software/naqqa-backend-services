package com.naqqa.sso.ldap;

import com.naqqa.sso.SsoException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

final class Ber {
    static final int BOOLEAN = 0x01;
    static final int INTEGER = 0x02;
    static final int OCTET_STRING = 0x04;
    static final int ENUMERATED = 0x0a;
    static final int SEQUENCE = 0x30;
    static final int SET = 0x31;

    private Ber() {
    }

    static byte[] length(int n) {
        if (n < 0) {
            throw new SsoException("ldap.encode_invalid");
        }
        if (n < 0x80) {
            return new byte[] {(byte) n};
        }
        int bytes = n > 0xffffff ? 4 : n > 0xffff ? 3 : n > 0xff ? 2 : 1;
        byte[] out = new byte[bytes + 1];
        out[0] = (byte) (0x80 | bytes);
        for (int i = 0; i < bytes; i++) {
            out[bytes - i] = (byte) (n >>> (8 * i));
        }
        return out;
    }

    static byte[] tlv(int tag, byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) {
            total += p.length;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(total + 6);
        out.write(tag);
        out.writeBytes(length(total));
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    static byte[] integer(int tag, long v) {
        int bytes = 1;
        while (bytes < 8 && (v >= (1L << (8 * bytes - 1)) || v < -(1L << (8 * bytes - 1)))) {
            bytes++;
        }
        byte[] c = new byte[bytes];
        for (int i = 0; i < bytes; i++) {
            c[bytes - 1 - i] = (byte) (v >>> (8 * i));
        }
        return tlv(tag, c);
    }

    static byte[] integer(long v) {
        return integer(INTEGER, v);
    }

    static byte[] enumerated(long v) {
        return integer(ENUMERATED, v);
    }

    static byte[] bool(boolean v) {
        return tlv(BOOLEAN, new byte[] {(byte) (v ? 0xff : 0x00)});
    }

    static byte[] octets(int tag, byte[] v) {
        return tlv(tag, v);
    }

    static byte[] octets(byte[] v) {
        return tlv(OCTET_STRING, v);
    }

    static byte[] octets(String v) {
        return tlv(OCTET_STRING, v.getBytes(StandardCharsets.UTF_8));
    }
}
