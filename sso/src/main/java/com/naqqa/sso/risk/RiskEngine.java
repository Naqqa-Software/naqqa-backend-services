package com.naqqa.sso.risk;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class RiskEngine {
    private final Clock clock;
    private final RiskConfig config;

    public RiskEngine(Clock clock, RiskConfig config) {
        this.clock = clock;
        this.config = config;
    }

    public RiskEngine(Clock clock) {
        this(clock, RiskConfig.defaults());
    }

    public static String networkOf(String ip) {
        if (ip == null || ip.isEmpty() || !ip.matches("[0-9a-fA-F:.]+")
                || (ip.indexOf(':') < 0 && !ip.matches("(25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})(\\.(25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})){3}"))
                || (ip.indexOf(':') >= 0 && ip.indexOf('.') >= 0 && !ip.matches("[0-9a-fA-F:]+:(25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})(\\.(25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})){3}"))) {
            return "invalid";
        }
        try {
            byte[] raw = InetAddress.getByName(ip).getAddress();
            int keep = raw.length == 4 ? 3 : 6;
            StringBuilder sb = new StringBuilder(raw.length == 4 ? "v4:" : "v6:");
            for (int i = 0; i < keep; i++) {
                sb.append(String.format("%02x", raw[i] & 0xFF));
            }
            return sb.toString();
        } catch (UnknownHostException e) {
            return "invalid";
        }
    }

    public RiskDecision evaluate(RiskContext ctx, RiskProfile profile) {
        int score = 0;
        List<String> reasons = new ArrayList<>();
        Instant now = clock.instant();
        if (ctx.deviceId() == null || !profile.knownDevices().contains(ctx.deviceId())) {
            score += config.newDevice();
            reasons.add("new_device");
        }
        if (!profile.knownNetworks().contains(networkOf(ctx.ip()))) {
            score += config.newNetwork();
            reasons.add("new_network");
        }
        if (ctx.asn() != null && !profile.knownAsns().isEmpty() && !profile.knownAsns().contains(ctx.asn())) {
            score += config.asnChange();
            reasons.add("asn_change");
        }
        if (ctx.geo() != null && profile.lastGeo() != null && profile.lastLoginTime() != null) {
            double km = ctx.geo().distanceKm(profile.lastGeo());
            double hours = Duration.between(profile.lastLoginTime(), now).toMillis() / 3_600_000.0;
            if (km >= config.minTravelKm()) {
                boolean impossible = hours <= 0 || km / hours > config.maxSpeedKmh();
                if (impossible) {
                    score += config.impossibleTravel();
                    reasons.add("impossible_travel");
                }
            }
        }
        if (ctx.recentFailures() >= 5) {
            score += config.failureVelocityHigh();
            reasons.add("failure_velocity");
        } else if (ctx.recentFailures() >= 3) {
            score += config.failureVelocityLow();
            reasons.add("failure_velocity");
        }
        if (ctx.torOrProxy()) {
            score += config.torOrProxy();
            reasons.add("tor_or_proxy");
        }
        if (!profile.usualHours().isEmpty()) {
            int hour = now.atZone(profile.zone()).getHour();
            if (!profile.usualHours().contains(hour)) {
                score += config.unusualHour();
                reasons.add("unusual_hour");
            }
        }
        RiskDecision.Action action = score >= config.denyThreshold() ? RiskDecision.Action.DENY
                : score >= config.stepUpThreshold() ? RiskDecision.Action.STEP_UP : RiskDecision.Action.ALLOW;
        return new RiskDecision(action, score, reasons);
    }
}
