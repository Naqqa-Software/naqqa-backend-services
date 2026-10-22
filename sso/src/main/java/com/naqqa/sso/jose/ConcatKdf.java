package com.naqqa.sso.jose;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class ConcatKdf {
    private ConcatKdf() {
    }

    public static byte[] derive(byte[] z, int keyBits, String algorithmId, byte[] apu, byte[] apv) {
        if (z == null || keyBits <= 0 || keyBits % 8 != 0 || keyBits > 512) {
            throw new SsoException("jwe.kdf_invalid");
        }
        byte[] otherInfo = Bytes.concat(
                lengthPrefixed(algorithmId.getBytes(StandardCharsets.US_ASCII)),
                lengthPrefixed(apu == null ? new byte[0] : apu),
                lengthPrefixed(apv == null ? new byte[0] : apv),
                int32(keyBits));
        int keyBytes = keyBits / 8;
        byte[] out = new byte[keyBytes];
        int offset = 0;
        for (int round = 1; offset < keyBytes; round++) {
            byte[] h = Hashes.sha256(Bytes.concat(int32(round), z, otherInfo));
            int n = Math.min(h.length, keyBytes - offset);
            System.arraycopy(h, 0, out, offset, n);
            offset += n;
            Arrays.fill(h, (byte) 0);
        }
        return out;
    }

    private static byte[] lengthPrefixed(byte[] data) {
        return Bytes.concat(int32(data.length), data);
    }

    private static byte[] int32(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }
}
