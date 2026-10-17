package com.naqqa.sso.risk;

public record RiskContext(String deviceId, String ip, String asn, GeoPoint geo, boolean torOrProxy, int recentFailures) {
}
