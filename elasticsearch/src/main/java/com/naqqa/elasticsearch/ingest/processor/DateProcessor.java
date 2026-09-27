package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.AbstractProcessor;
import com.naqqa.elasticsearch.ingest.ConfigurationUtils;
import com.naqqa.elasticsearch.ingest.IngestDocument;
import com.naqqa.elasticsearch.ingest.Processor;
import com.naqqa.elasticsearch.ingest.ProcessorRegistry;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DateProcessor extends AbstractProcessor {

    public static final String TYPE = "date";
    private static final DateTimeFormatter DEFAULT_OUTPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private final String field;
    private final String targetField;
    private final List<String> formats;
    private final String timezone;
    private final Locale locale;
    private final String outputFormat;

    public DateProcessor(String tag, String description, String field, String targetField, List<String> formats,
                          String timezone, Locale locale, String outputFormat) {
        super(TYPE, tag, description);
        this.field = field;
        this.targetField = targetField;
        this.formats = formats;
        this.timezone = timezone;
        this.locale = locale;
        this.outputFormat = outputFormat;
    }

    @Override
    public IngestDocument execute(IngestDocument document) {
        String resolvedField = document.renderTemplate(field);
        Object raw = document.getFieldValue(resolvedField, Object.class);
        ZoneId zone = timezone != null ? ZoneId.of(document.renderTemplate(timezone)) : ZoneId.of("UTC");
        ZonedDateTime parsed = DateFormats.parseAny(raw, formats, zone, locale);
        String formatted = outputFormat != null
            ? parsed.format(DateTimeFormatter.ofPattern(outputFormat, locale == null ? Locale.ROOT : locale))
            : parsed.format(DEFAULT_OUTPUT);
        document.setFieldValue(document.renderTemplate(targetField), formatted);
        return document;
    }

    public static final class Factory implements Processor.Factory {
        @Override
        @SuppressWarnings("unchecked")
        public Processor create(ProcessorRegistry registry, String tag, String description, Map<String, Object> config) {
            String field = ConfigurationUtils.readStringProperty(TYPE, tag, config, "field");
            String targetField = ConfigurationUtils.readStringProperty(TYPE, tag, config, "target_field", "@timestamp");
            List<String> formats = ConfigurationUtils.readOptionalStringList(config, "formats");
            if (formats == null) {
                formats = List.of("ISO8601");
            }
            String timezone = ConfigurationUtils.readOptionalStringProperty(config, "timezone");
            String localeStr = ConfigurationUtils.readOptionalStringProperty(config, "locale");
            Locale locale = localeStr == null ? null : Locale.forLanguageTag(localeStr);
            String outputFormat = ConfigurationUtils.readOptionalStringProperty(config, "output_format");
            return new DateProcessor(tag, description, field, targetField, formats, timezone, locale, outputFormat);
        }
    }
}
