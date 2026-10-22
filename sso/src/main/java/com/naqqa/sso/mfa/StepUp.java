package com.naqqa.sso.mfa;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

public final class StepUp {
    public static final String ACR_PASSWORD = "urn:naqqa:acr:1";
    public static final String ACR_MFA = "urn:naqqa:acr:2";
    public static final String ACR_PHISHING_RESISTANT = "urn:naqqa:acr:3";

    public enum Reason {
        NONE, AUTH_TOO_OLD, ACR_TOO_LOW, FACTOR_NOT_ALLOWED
    }

    public record AuthContext(Instant authTime, Set<String> amr) {
        public AuthContext {
            amr = Set.copyOf(amr);
        }
    }

    public record Result(boolean satisfied, Reason reason, String acr) {
    }

    private final Clock clock;
    private final MfaPolicy policy;

    public StepUp(Clock clock, MfaPolicy policy) {
        this.clock = clock;
        this.policy = policy;
    }

    public static int level(Set<String> amr) {
        boolean pwd = amr.contains("pwd");
        boolean possession = amr.contains("hwk") || amr.contains("swk");
        boolean verified = amr.contains("user") || amr.contains("face") || amr.contains("fpt") || amr.contains("pin");
        boolean second = amr.contains("otp") || amr.contains("sms") || amr.contains("mca") || amr.contains("mfa");
        if (possession && verified) {
            return 3;
        }
        if ((pwd && (second || possession)) || amr.contains("mfa")) {
            return 2;
        }
        if (pwd || second || possession) {
            return 1;
        }
        return 0;
    }

    public static String acr(Set<String> amr) {
        return switch (level(amr)) {
            case 3 -> ACR_PHISHING_RESISTANT;
            case 2 -> ACR_MFA;
            default -> ACR_PASSWORD;
        };
    }

    public static int levelOfAcr(String acr) {
        return switch (acr == null ? "" : acr) {
            case ACR_PHISHING_RESISTANT -> 3;
            case ACR_MFA -> 2;
            case ACR_PASSWORD -> 1;
            default -> 0;
        };
    }

    public Result evaluate(AuthContext ctx, String requiredAcr, boolean enforceMaxAge) {
        String actual = acr(ctx.amr());
        Set<String> allowed = policy.allowedAmr();
        for (String m : ctx.amr()) {
            if (!allowed.contains(m) && !m.equals("mfa")) {
                return new Result(false, Reason.FACTOR_NOT_ALLOWED, actual);
            }
        }
        if (enforceMaxAge) {
            Duration age = Duration.between(ctx.authTime(), clock.instant());
            if (age.compareTo(policy.stepUpMaxAge()) > 0) {
                return new Result(false, Reason.AUTH_TOO_OLD, actual);
            }
        }
        if (level(ctx.amr()) < levelOfAcr(requiredAcr)) {
            return new Result(false, Reason.ACR_TOO_LOW, actual);
        }
        return new Result(true, Reason.NONE, actual);
    }
}
