package com.naqqa.sso.mfa;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Hashes;

public final class Hotp {
    private static final int[] POW10 = {1, 10, 100, 1000, 10000, 100000, 1000000, 10000000, 100000000};

    private Hotp() {
    }

    public static String generate(byte[] secret, long counter, int digits, OtpAlgorithm algorithm) {
        if (secret == null || secret.length < 10) {
            throw new SsoException("otp_secret_invalid");
        }
        if (digits < 6 || digits > 8) {
            throw new SsoException("otp_digits_invalid");
        }
        if (counter < 0) {
            throw new SsoException("otp_counter_invalid");
        }
        byte[] msg = new byte[8];
        long c = counter;
        for (int i = 7; i >= 0; i--) {
            msg[i] = (byte) (c & 0xFF);
            c >>>= 8;
        }
        byte[] mac = Hashes.hmac(algorithm.macName(), secret, msg);
        int offset = mac[mac.length - 1] & 0x0F;
        int binary = ((mac[offset] & 0x7F) << 24)
                | ((mac[offset + 1] & 0xFF) << 16)
                | ((mac[offset + 2] & 0xFF) << 8)
                | (mac[offset + 3] & 0xFF);
        int otp = binary % POW10[digits];
        String s = Integer.toString(otp);
        StringBuilder sb = new StringBuilder(digits);
        for (int i = s.length(); i < digits; i++) {
            sb.append('0');
        }
        return sb.append(s).toString();
    }

    public static String generate(byte[] secret, long counter) {
        return generate(secret, counter, 6, OtpAlgorithm.SHA1);
    }
}
