package com.naqqa.sso.crypto;

import com.naqqa.sso.SsoException;

import java.util.Arrays;

final class Der {
    static final int SEQUENCE = 0x30;
    static final int INTEGER = 0x02;
    static final int BIT_STRING = 0x03;
    static final int OCTET_STRING = 0x04;
    static final int NULL = 0x05;
    static final int OID = 0x06;

    private final byte[] data;
    private int pos;
    private final int end;

    Der(byte[] data) {
        this(data, 0, data.length);
    }

    private Der(byte[] data, int start, int end) {
        this.data = data;
        this.pos = start;
        this.end = end;
    }

    record Tlv(int tag, byte[] value) {
        Der reader() {
            return new Der(value);
        }
    }

    boolean hasMore() {
        return pos < end;
    }

    int peekTag() {
        if (pos >= end) {
            throw new SsoException("crypto.der_invalid");
        }
        return data[pos] & 0xFF;
    }

    Tlv read() {
        if (pos + 2 > end) {
            throw new SsoException("crypto.der_invalid");
        }
        int tag = data[pos++] & 0xFF;
        int first = data[pos++] & 0xFF;
        int len;
        if (first < 0x80) {
            len = first;
        } else {
            int n = first & 0x7F;
            if (n == 0 || n > 3 || pos + n > end) {
                throw new SsoException("crypto.der_invalid");
            }
            len = 0;
            for (int i = 0; i < n; i++) {
                len = (len << 8) | (data[pos++] & 0xFF);
            }
            if (len < 0x80 || (n > 1 && len < (1 << (8 * (n - 1))))) {
                throw new SsoException("crypto.der_invalid");
            }
        }
        if (len > end - pos) {
            throw new SsoException("crypto.der_invalid");
        }
        byte[] value = Arrays.copyOfRange(data, pos, pos + len);
        pos += len;
        return new Tlv(tag, value);
    }

    Tlv read(int expectedTag) {
        Tlv t = read();
        if (t.tag() != expectedTag) {
            throw new SsoException("crypto.der_invalid");
        }
        return t;
    }

    void finish() {
        if (pos != end) {
            throw new SsoException("crypto.der_invalid");
        }
    }

    static byte[] encode(int tag, byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) {
            len += p.length;
        }
        byte[] header;
        if (len < 0x80) {
            header = new byte[]{(byte) tag, (byte) len};
        } else if (len < 0x100) {
            header = new byte[]{(byte) tag, (byte) 0x81, (byte) len};
        } else if (len < 0x10000) {
            header = new byte[]{(byte) tag, (byte) 0x82, (byte) (len >> 8), (byte) len};
        } else {
            header = new byte[]{(byte) tag, (byte) 0x83, (byte) (len >> 16), (byte) (len >> 8), (byte) len};
        }
        byte[] out = new byte[header.length + len];
        System.arraycopy(header, 0, out, 0, header.length);
        int off = header.length;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }
}
