package com.naqqa.analytics.spi;

import java.net.InetAddress;

public interface AnalyticsGeoResolver {

    AnalyticsGeoResolver NONE = new AnalyticsGeoResolver() {
    };

    default Geo lookup(InetAddress address) {
        return null;
    }

    record Geo(String country, String region, String city) {
    }
}
