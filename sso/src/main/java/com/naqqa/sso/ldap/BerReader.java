package com.naqqa.sso.ldap;

import com.naqqa.sso.SsoException;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class BerReader {
    static final int MAX_DEPTH = 8;

    private final byte[] data;
    private int pos;
    private final int end;
    private final int depth;

    BerReader(byte[] data) {
        this(data, 0, data.length, 0);
    }

    private BerReader(byte[] data, int pos, int end, int depth) {
        this.data = data;
        this.pos = pos;
        this.end = end;
        this.depth = depth;
    }

    static SsoException protocol() {
        return new SsoException("ldap.protocol_error");
    }

    boolean hasMore() {
        return pos < end;
    }

    int peekTag() {
        if (pos >= end) {
            throw protocol();
        }
        return data[pos] & 0xff;
    }

    private int[] header() {
        if (pos >= end) {
            throw protocol();
        }
        int tag = data[pos++] & 0xff;
        if ((tag & 0x1f) == 0x1f || pos >= end) {
            throw protocol();
        }
        int first = data[pos++] & 0xff;
        int len;
        if (first < 0x80) {
            len = first;
        } else {
            int n = first & 0x7f;
            if (first == 0x80 || n > 4 || pos + n > end) {
                throw protocol();
            }
            if ((data[pos] & 0xff) == 0) {
                throw protocol();
            }
            long v = 0;
            for (int i = 0; i < n; i++) {
                v = (v << 8) | (data[pos++] & 0xff);
            }
            if (v < 0x80 || v > Integer.MAX_VALUE) {
                throw protocol();
            }
            len = (int) v;
        }
        if (len > end - pos) {
            throw protocol();
        }
        return new int[] {tag, len};
    }

    BerReader enter(int expectedTag) {
        if (depth + 1 > MAX_DEPTH) {
            throw protocol();
        }
        int[] h = header();
        if (h[0] != expectedTag) {
            throw protocol();
        }
        BerReader child = new BerReader(data, pos, pos + h[1], depth + 1);
        pos += h[1];
        return child;
    }

    byte[] octets(int expectedTag) {
        int[] h = header();
        if (h[0] != expectedTag) {
            throw protocol();
        }
        byte[] out = Arrays.copyOfRange(data, pos, pos + h[1]);
        pos += h[1];
        return out;
    }

    String string(int expectedTag) {
        return utf8(octets(expectedTag));
    }

    static String utf8(byte[] raw) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException e) {
            throw protocol();
        }
    }

    long integer(int expectedTag) {
        byte[] c = octets(expectedTag);
        if (c.length == 0 || c.length > 8) {
            throw protocol();
        }
        if (c.length > 1 && ((c[0] == 0 && (c[1] & 0x80) == 0) || (c[0] == (byte) 0xff && (c[1] & 0x80) != 0))) {
            throw protocol();
        }
        long v = (c[0] & 0x80) != 0 ? -1L : 0L;
        for (byte b : c) {
            v = (v << 8) | (b & 0xff);
        }
        return v;
    }

    void skip() {
        int[] h = header();
        pos += h[1];
    }

    void finish() {
        if (pos != end) {
            throw protocol();
        }
    }
}
