package com.naqqa.analytics.collect;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.CityResponse;
import com.naqqa.analytics.spi.AnalyticsGeoResolver;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;

@Slf4j
public class MaxMindGeoResolver implements AnalyticsGeoResolver, AutoCloseable {

    private final DatabaseReader reader;

    public MaxMindGeoResolver(String path) throws IOException {
        this.reader = new DatabaseReader.Builder(new File(path)).build();
    }

    @Override
    public Geo lookup(InetAddress address) {
        if (address == null || address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()) {
            return null;
        }
        try {
            CityResponse r = reader.tryCity(address).orElse(null);
            if (r == null) {
                return null;
            }
            String country = r.getCountry() == null ? null : r.getCountry().getIsoCode();
            String region = r.getMostSpecificSubdivision() == null ? null : r.getMostSpecificSubdivision().getName();
            String city = r.getCity() == null ? null : r.getCity().getName();
            return new Geo(country, region, city);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
