package com.naqqa.sso.session;

import com.naqqa.sso.SsoException;

import java.time.Duration;

public final class CookieBuilder {
    public enum SameSite {
        STRICT("Strict"), LAX("Lax"), NONE("None");

        private final String value;

        SameSite(String value) {
            this.value = value;
        }
    }

    private final String name;
    private final String value;
    private boolean secure = true;
    private boolean httpOnly = true;
    private SameSite sameSite = SameSite.LAX;
    private String path = "/";
    private String domain;
    private Duration maxAge;
    private boolean hostPrefix;

    private CookieBuilder(String name, String value) {
        this.name = name;
        this.value = value;
    }

    public static CookieBuilder of(String name, String value) {
        if (name == null || !name.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]+")) {
            throw new SsoException("cookie_invalid");
        }
        if (value == null || !value.matches("[\\x21\\x23-\\x2B\\x2D-\\x3A\\x3C-\\x5B\\x5D-\\x7E]*")) {
            throw new SsoException("cookie_invalid");
        }
        return new CookieBuilder(name, value);
    }

    public CookieBuilder secure(boolean v) {
        this.secure = v;
        return this;
    }

    public CookieBuilder httpOnly(boolean v) {
        this.httpOnly = v;
        return this;
    }

    public CookieBuilder sameSite(SameSite v) {
        this.sameSite = v;
        return this;
    }

    public CookieBuilder path(String v) {
        if (v == null || !v.startsWith("/") || v.matches(".*[;\\r\\n\\s].*")) {
            throw new SsoException("cookie_invalid");
        }
        this.path = v;
        return this;
    }

    public CookieBuilder domain(String v) {
        if (v != null && !v.matches("[A-Za-z0-9.-]+")) {
            throw new SsoException("cookie_invalid");
        }
        this.domain = v;
        return this;
    }

    public CookieBuilder maxAge(Duration v) {
        this.maxAge = v;
        return this;
    }

    public CookieBuilder hostPrefix() {
        this.hostPrefix = true;
        return this;
    }

    public String build() {
        if (sameSite == SameSite.NONE && !secure) {
            throw new SsoException("cookie_invalid");
        }
        String finalName = name;
        if (hostPrefix) {
            if (!secure || domain != null || !path.equals("/")) {
                throw new SsoException("cookie_invalid");
            }
            finalName = name.startsWith("__Host-") ? name : "__Host-" + name;
        }
        StringBuilder sb = new StringBuilder(finalName).append('=').append(value);
        if (maxAge != null) {
            sb.append("; Max-Age=").append(maxAge.getSeconds());
        }
        sb.append("; Path=").append(path);
        if (domain != null) {
            sb.append("; Domain=").append(domain);
        }
        if (secure) {
            sb.append("; Secure");
        }
        if (httpOnly) {
            sb.append("; HttpOnly");
        }
        sb.append("; SameSite=").append(sameSite.value);
        return sb.toString();
    }

    public static String expire(String name, boolean hostPrefix) {
        CookieBuilder b = of(name, "").maxAge(Duration.ZERO);
        if (hostPrefix) {
            b.hostPrefix();
        }
        return b.build();
    }
}
