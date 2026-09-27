package com.naqqa.elasticsearch.ingest.geoip;

public record GeoIpRecord(String continentName, String countryIsoCode, String countryName, String regionName,
                           String cityName, String timezone, String postalCode, Double latitude, Double longitude) {
}
