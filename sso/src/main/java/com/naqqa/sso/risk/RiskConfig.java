package com.naqqa.sso.risk;

import com.naqqa.sso.SsoException;

public record RiskConfig(int stepUpThreshold, int denyThreshold, int newDevice, int newNetwork, int asnChange, int impossibleTravel,
                         int failureVelocityHigh, int failureVelocityLow, int torOrProxy, int unusualHour, double maxSpeedKmh,
                         double minTravelKm) {
    public RiskConfig {
        if (stepUpThreshold < 1 || denyThreshold <= stepUpThreshold || maxSpeedKmh <= 0 || minTravelKm < 0) {
            throw new SsoException("risk_config_invalid");
        }
    }

    public static RiskConfig defaults() {
        return new RiskConfig(30, 80, 25, 20, 15, 50, 30, 15, 30, 10, 900.0, 100.0);
    }
}
