package com.naqqa.authenticator.core;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class OtpAuthUri {

    private OtpAuthUri() {
    }

    public static String totp(String issuer, String account, String base32Secret, TotpParameters parameters) {
        String safeIssuer = issuer == null || issuer.isBlank() ? "naqqa" : issuer.replace(":", "").trim();
        String safeAccount = account == null || account.isBlank() ? safeIssuer : account.replace(":", "").trim();
        return "otpauth://totp/" + encode(safeIssuer) + ":" + encode(safeAccount)
                + "?secret=" + base32Secret
                + "&issuer=" + encode(safeIssuer)
                + "&algorithm=" + parameters.algorithm().uriName()
                + "&digits=" + parameters.digits()
                + "&period=" + parameters.periodSeconds();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
