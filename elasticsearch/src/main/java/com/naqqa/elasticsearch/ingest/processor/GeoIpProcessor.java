package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.AbstractProcessor;
import com.naqqa.elasticsearch.ingest.ConfigurationUtils;
import com.naqqa.elasticsearch.ingest.IngestDocument;
import com.naqqa.elasticsearch.ingest.Processor;
import com.naqqa.elasticsearch.ingest.ProcessorRegistry;
import com.naqqa.elasticsearch.ingest.geoip.GeoIpDatabaseReader;
import com.naqqa.elasticsearch.ingest.geoip.GeoIpRecord;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class GeoIpProcessor extends AbstractProcessor {

    public static final String TYPE = "geoip";

    private final String field;
    private final String targetField;
    private final GeoIpDatabaseReader database;
    private final boolean ignoreMissing;

    public GeoIpProcessor(String tag, String description, String field, String targetField, GeoIpDatabaseReader database, boolean ignoreMissing) {
        super(TYPE, tag, description);
        this.field = field;
        this.targetField = targetField;
        this.database = database;
        this.ignoreMissing = ignoreMissing;
    }

    @Override
    public IngestDocument execute(IngestDocument document) {
        String resolvedField = document.renderTemplate(field);
        String ip = document.getFieldValue(resolvedField, String.class, ignoreMissing);
        if (ip == null) {
            return document;
        }
        GeoIpRecord record = database.lookup(ip);
        if (record == null) {
            return document;
        }
        Map<String, Object> geo = new LinkedHashMap<>();
        if (record.countryIsoCode() != null) {
            geo.put("country_iso_code", record.countryIsoCode());
        }
        if (record.countryName() != null) {
            geo.put("country_name", record.countryName());
        }
        if (record.continentName() != null) {
            geo.put("continent_name", record.continentName());
        }
        if (record.regionName() != null) {
            geo.put("region_name", record.regionName());
        }
        if (record.cityName() != null) {
            geo.put("city_name", record.cityName());
        }
        if (record.timezone() != null) {
            geo.put("timezone", record.timezone());
        }
        if (record.postalCode() != null) {
            geo.put("postal_code", record.postalCode());
        }
        if (record.latitude() != null && record.longitude() != null) {
            Map<String, Object> location = new LinkedHashMap<>();
            location.put("lat", record.latitude());
            location.put("lon", record.longitude());
            geo.put("location", location);
        }
        document.setFieldValue(document.renderTemplate(targetField), geo);
        return document;
    }

    public static final class Factory implements Processor.Factory {
        @Override
        public Processor create(ProcessorRegistry registry, String tag, String description, Map<String, Object> config) {
            String field = ConfigurationUtils.readStringProperty(TYPE, tag, config, "field");
            String targetField = ConfigurationUtils.readStringProperty(TYPE, tag, config, "target_field", "geoip");
            String databaseFile = ConfigurationUtils.readStringProperty(TYPE, tag, config, "database_file");
            boolean ignoreMissing = ConfigurationUtils.readBooleanProperty(TYPE, tag, config, "ignore_missing", false);
            GeoIpDatabaseReader reader;
            try {
                reader = GeoIpDatabaseReader.load(Path.of(databaseFile));
            } catch (IOException e) {
                throw new com.naqqa.elasticsearch.ingest.ConfigurationException("unable to load geoip database [" + databaseFile + "]", e);
            }
            return new GeoIpProcessor(tag, description, field, targetField, reader, ignoreMissing);
        }
    }
}
