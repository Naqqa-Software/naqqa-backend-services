package com.naqqa.sso.webauthn;

import com.naqqa.sso.SsoException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class Der {
    static final int CLASS_UNIVERSAL = 0;
    static final int CLASS_CONTEXT = 2;

    record Tlv(int tagClass, boolean constructed, int number, byte[] value) {
        List<Tlv> children() {
            if (!constructed) {
                throw new SsoException("webauthn.der.invalid");
            }
            return readAll(value);
        }

        boolean is(int cls, int num) {
            return tagClass == cls && number == num;
        }
    }

    private Der() {
    }

    static Tlv readSingle(byte[] data) {
        int[] pos = {0};
        Tlv t = read(data, pos);
        if (pos[0] != data.length) {
            throw new SsoException("webauthn.der.invalid");
        }
        return t;
    }

    static List<Tlv> readAll(byte[] data) {
        List<Tlv> out = new ArrayList<>();
        int[] pos = {0};
        while (pos[0] < data.length) {
            if (out.size() > 512) {
                throw new SsoException("webauthn.der.invalid");
            }
            out.add(read(data, pos));
        }
        return out;
    }

    private static int u8(byte[] d, int[] pos) {
        if (pos[0] >= d.length) {
            throw new SsoException("webauthn.der.invalid");
        }
        return d[pos[0]++] & 0xFF;
    }

    static Tlv read(byte[] d, int[] pos) {
        int first = u8(d, pos);
        int cls = first >>> 6;
        boolean constructed = (first & 0x20) != 0;
        int number = first & 0x1F;
        if (number == 0x1F) {
            number = 0;
            int b;
            int n = 0;
            do {
                b = u8(d, pos);
                if (++n > 3 || (n == 1 && b == 0x80)) {
                    throw new SsoException("webauthn.der.invalid");
                }
                number = (number << 7) | (b & 0x7F);
            } while ((b & 0x80) != 0);
            if (number < 0x1F) {
                throw new SsoException("webauthn.der.invalid");
            }
        }
        int lb = u8(d, pos);
        int len;
        if (lb < 0x80) {
            len = lb;
        } else {
            int n = lb & 0x7F;
            if (n == 0 || n > 3) {
                throw new SsoException("webauthn.der.invalid");
            }
            len = 0;
            for (int i = 0; i < n; i++) {
                int b = u8(d, pos);
                if (i == 0 && b == 0) {
                    throw new SsoException("webauthn.der.invalid");
                }
                len = (len << 8) | b;
            }
            if (len < 0x80) {
                throw new SsoException("webauthn.der.invalid");
            }
        }
        if (len > d.length - pos[0]) {
            throw new SsoException("webauthn.der.invalid");
        }
        byte[] value = Arrays.copyOfRange(d, pos[0], pos[0] + len);
        pos[0] += len;
        return new Tlv(cls, constructed, number, value);
    }

    static byte[] unwrapOctetString(byte[] data) {
        Tlv t = readSingle(data);
        if (!t.is(CLASS_UNIVERSAL, 4) || t.constructed()) {
            throw new SsoException("webauthn.der.invalid");
        }
        return t.value();
    }

    static String oid(byte[] v) {
        if (v.length == 0 || v.length > 64 || (v[v.length - 1] & 0x80) != 0) {
            throw new SsoException("webauthn.der.invalid");
        }
        StringBuilder sb = new StringBuilder();
        long acc = 0;
        boolean start = true;
        boolean first = true;
        for (byte x : v) {
            int b = x & 0xFF;
            if (start && b == 0x80) {
                throw new SsoException("webauthn.der.invalid");
            }
            acc = (acc << 7) | (b & 0x7F);
            if (acc > Integer.MAX_VALUE) {
                throw new SsoException("webauthn.der.invalid");
            }
            start = (b & 0x80) == 0;
            if (start) {
                if (first) {
                    int a = acc < 40 ? 0 : acc < 80 ? 1 : 2;
                    sb.append(a).append('.').append(acc - 40L * a);
                    first = false;
                } else {
                    sb.append('.').append(acc);
                }
                acc = 0;
            }
        }
        return sb.toString();
    }

    static long integer(Tlv t) {
        if (!t.is(CLASS_UNIVERSAL, 2) || t.constructed() || t.value().length == 0 || t.value().length > 8) {
            throw new SsoException("webauthn.der.invalid");
        }
        byte[] b = t.value();
        if (b.length > 1 && ((b[0] == 0 && (b[1] & 0x80) == 0) || (b[0] == (byte) 0xFF && (b[1] & 0x80) != 0))) {
            throw new SsoException("webauthn.der.invalid");
        }
        long v = t.value()[0];
        for (int i = 1; i < t.value().length; i++) {
            v = (v << 8) | (t.value()[i] & 0xFF);
        }
        return v;
    }
}
