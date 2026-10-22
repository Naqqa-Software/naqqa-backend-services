package com.naqqa.sso.risk;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;

public record RiskProfile(Set<String> knownDevices, Set<String> knownNetworks, Set<String> knownAsns, GeoPoint lastGeo,
                          Instant lastLoginTime, Set<Integer> usualHours, ZoneId zone) {
    public RiskProfile {
        knownDevices = Set.copyOf(knownDevices);
        knownNetworks = Set.copyOf(knownNetworks);
        knownAsns = Set.copyOf(knownAsns);
        usualHours = Set.copyOf(usualHours);
    }

    public static RiskProfile empty() {
        return new RiskProfile(Set.of(), Set.of(), Set.of(), null, null, Set.of(), ZoneId.of("UTC"));
    }
}
