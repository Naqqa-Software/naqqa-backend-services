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

public final class DateIndexNameProcessor extends AbstractProcessor {

    public static final String TYPE = "date_index_name";

    private final String field;
    private final String indexNamePrefix;
    private final String dateRounding;
    private final List<String> dateFormats;
    private final String timezone;
    private final Locale locale;
    private final String indexNameFormat;

    public DateIndexNameProcessor(String tag, String description, String field, String indexNamePrefix, String dateRounding,
                                   List<String> dateFormats, String timezone, Locale locale, String indexNameFormat) {
        super(TYPE, tag, description);
        this.field = field;
        this.indexNamePrefix = indexNamePrefix;
        this.dateRounding = dateRounding;
        this.dateFormats = dateFormats;
        this.timezone = timezone;
        this.locale = locale;
        this.indexNameFormat = indexNameFormat;
    }

    @Override
    public IngestDocument execute(IngestDocument document) {
        String resolvedField = document.renderTemplate(field);
        Object raw = document.getFieldValue(resolvedField, Object.class);
        ZoneId zone = timezone != null ? ZoneId.of(document.renderTemplate(timezone)) : ZoneId.of("UTC");
        ZonedDateTime parsed = DateFormats.parseAny(raw, dateFormats, zone, locale);
        ZonedDateTime rounded = round(parsed, dateRounding);
        String formatted = rounded.format(DateTimeFormatter.ofPattern(indexNameFormat, locale == null ? Locale.ROOT : locale));
        document.setIndex(indexNamePrefix + formatted);
        return document;
    }

    private static ZonedDateTime round(ZonedDateTime time, String unit) {
        return switch (unit) {
            case "y" -> time.withDayOfYear(1).truncatedTo(java.time.temporal.ChronoUnit.DAYS);
            case "M" -> time.withDayOfMonth(1).truncatedTo(java.time.temporal.ChronoUnit.DAYS);
            case "w" -> time.with(java.time.temporal.WeekFields.ISO.dayOfWeek(), 1).truncatedTo(java.time.temporal.ChronoUnit.DAYS);
            case "d" -> time.truncatedTo(java.time.temporal.ChronoUnit.DAYS);
            case "h" -> time.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
            case "m" -> time.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
            case "s" -> time.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            default -> throw new IllegalArgumentException("unsupported date_rounding [" + unit + "]");
        };
    }

    public static final class Factory implements Processor.Factory {
        @Override
        public Processor create(ProcessorRegistry registry, String tag, String description, Map<String, Object> config) {
            String field = ConfigurationUtils.readStringProperty(TYPE, tag, config, "field");
            String indexNamePrefix = ConfigurationUtils.readStringProperty(TYPE, tag, config, "index_name_prefix", "");
            String dateRounding = ConfigurationUtils.readStringProperty(TYPE, tag, config, "date_rounding", "d");
            List<String> dateFormats = ConfigurationUtils.readOptionalStringList(config, "date_formats");
            if (dateFormats == null) {
                dateFormats = List.of("yyyy-MM-dd'T'HH:mm:ss.SSSZ", "ISO8601");
            }
            String timezone = ConfigurationUtils.readOptionalStringProperty(config, "timezone");
            String localeStr = ConfigurationUtils.readOptionalStringProperty(config, "locale");
            Locale locale = localeStr == null ? null : Locale.forLanguageTag(localeStr);
            String indexNameFormat = ConfigurationUtils.readStringProperty(TYPE, tag, config, "index_name_format", "yyyy-MM-dd");
            return new DateIndexNameProcessor(tag, description, field, indexNamePrefix, dateRounding, dateFormats, timezone, locale, indexNameFormat);
        }
    }
}
