package com.naqqa.sso.mfa;

import com.naqqa.sso.SsoException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class OtpAuthUri {
    private OtpAuthUri() {
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void check(String issuer, String account) {
        if (issuer == null || issuer.isEmpty() || issuer.indexOf(':') >= 0 || account == null || account.isEmpty()) {
            throw new SsoException("otpauth_invalid");
        }
    }

    public static String totp(String issuer, String account, byte[] secret, TotpConfig config) {
        check(issuer, account);
        return "otpauth://totp/" + enc(issuer) + ":" + enc(account)
                + "?secret=" + Base32.encode(secret)
                + "&issuer=" + enc(issuer)
                + "&algorithm=" + config.algorithm().uriName()
                + "&digits=" + config.digits()
                + "&period=" + config.stepSeconds();
    }

    public static String hotp(String issuer, String account, byte[] secret, OtpAlgorithm algorithm, int digits, long counter) {
        check(issuer, account);
        return "otpauth://hotp/" + enc(issuer) + ":" + enc(account)
                + "?secret=" + Base32.encode(secret)
                + "&issuer=" + enc(issuer)
                + "&algorithm=" + algorithm.uriName()
                + "&digits=" + digits
                + "&counter=" + counter;
    }
}
