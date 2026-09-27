package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

import java.nio.charset.StandardCharsets;

public final class DelimitedPayloadFilter extends TokenFilter {

    public enum Encoding { IDENTITY, FLOAT, INT }

    private final char delimiter;
    private final Encoding encoding;

    public DelimitedPayloadFilter(TokenStream input, char delimiter, Encoding encoding) {
        super(input);
        this.delimiter = delimiter;
        this.encoding = encoding;
    }

    @Override
    public boolean incrementToken() {
        if (!input.incrementToken()) {
            return false;
        }
        char[] buf = token.buffer();
        int len = token.length();
        for (int i = 0; i < len; i++) {
            if (buf[i] == delimiter) {
                String payloadText = new String(buf, i + 1, len - i - 1);
                token.setPayload(encode(payloadText));
                token.setLength(i);
                break;
            }
        }
        return true;
    }

    private byte[] encode(String s) {
        return switch (encoding) {
            case IDENTITY -> s.getBytes(StandardCharsets.UTF_8);
            case FLOAT -> floatToBytes(Float.parseFloat(s));
            case INT -> intToBytes(Integer.parseInt(s));
        };
    }

    private static byte[] floatToBytes(float f) {
        int bits = Float.floatToIntBits(f);
        return intToBytes(bits);
    }

    private static byte[] intToBytes(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }
}
