package com.naqqa.sso.mfa;

import com.naqqa.sso.util.Bytes;

import java.time.Clock;

public final class Totp {
    public record Result(boolean valid, long step) {
    }

    private final Clock clock;
    private final TotpConfig config;
    private final TotpStepStore store;

    public Totp(Clock clock, TotpConfig config, TotpStepStore store) {
        this.clock = clock;
        this.config = config;
        this.store = store;
    }

    public static String generate(byte[] secret, long epochSeconds, TotpConfig config) {
        return Hotp.generate(secret, Math.floorDiv(epochSeconds, (long) config.stepSeconds()), config.digits(), config.algorithm());
    }

    public String current(byte[] secret) {
        return generate(secret, clock.instant().getEpochSecond(), config);
    }

    public long currentStep() {
        return Math.floorDiv(clock.instant().getEpochSecond(), (long) config.stepSeconds());
    }

    public Result verify(String userId, byte[] secret, String code) {
        if (code == null || code.length() != config.digits() || !code.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
            return new Result(false, -1);
        }
        long now = currentStep();
        long last = store.lastStep(userId);
        long matched = -1;
        for (long s = now - config.window(); s <= now + config.window(); s++) {
            if (s < 0) {
                continue;
            }
            String expected = Hotp.generate(secret, s, config.digits(), config.algorithm());
            if (Bytes.constantTimeEquals(expected, code) && s > last && s > matched) {
                matched = s;
            }
        }
        if (matched < 0) {
            return new Result(false, -1);
        }
        if (!store.advance(userId, matched)) {
            return new Result(false, -1);
        }
        return new Result(true, matched);
    }

    public TotpConfig config() {
        return config;
    }
}
