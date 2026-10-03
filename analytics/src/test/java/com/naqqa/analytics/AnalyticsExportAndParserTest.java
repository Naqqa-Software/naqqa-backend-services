package com.naqqa.analytics;

import com.naqqa.analytics.export.CsvWriter;
import com.naqqa.analytics.export.Table;
import com.naqqa.analytics.export.XlsxWriter;
import com.naqqa.analytics.query.AnalyticsQuery;
import com.naqqa.analytics.query.EventFilter;
import com.naqqa.analytics.reports.ReportSchedule;
import com.naqqa.analytics.web.AnalyticsException;
import com.naqqa.analytics.web.ImpersonationTokens;
import com.naqqa.analytics.web.QueryParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalyticsExportAndParserTest {

    private final AnalyticsTestSupport.MutableClock clock = new AnalyticsTestSupport.MutableClock(Instant.parse("2026-10-03T12:00:00Z"));

    @Test
    void csvEscapesAndNeutralizesFormulas() {
        Table t = Table.of("T", "a", "b", "c").row("=HYPERLINK(\"x\")", "x,y", 12).row(null, "line\nbreak", -1.5);
        String csv = new String(CsvWriter.write(List.of(t)), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿a,b,c\r\n");
        assertThat(csv).contains("\"'=HYPERLINK(\"\"x\"\")\",\"x,y\",12\r\n");
        assertThat(csv).contains(",\"line\nbreak\",-1.5\r\n");
    }

    @Test
    void xlsxIsAValidPackage() throws IOException {
        Table a = Table.of("Items/1", "name", "count").row("Lapte & <pâine>", 3).row("x", true);
        Table b = Table.of("Items/1", "k").row("v");
        byte[] data = XlsxWriter.write(List.of(a, b));
        List<String> names = new ArrayList<>();
        String sheet1 = null;
        String workbook = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                names.add(e.getName());
                String content = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                if (e.getName().equals("xl/worksheets/sheet1.xml")) {
                    sheet1 = content;
                }
                if (e.getName().equals("xl/workbook.xml")) {
                    workbook = content;
                }
            }
        }
        assertThat(names).contains("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels",
                "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml");
        assertThat(sheet1).contains("Lapte &amp; &lt;pâine&gt;").contains("<v>3</v>").contains("t=\"b\"><v>1</v>");
        assertThat(workbook).contains("name=\"Items_1\"").contains("name=\"Items_1_2\"");
    }

    @Test
    void queryParserDefaultsAndLimits() {
        AnalyticsQuery q = QueryParser.parse(Map.of(), AnalyticsTestSupport.ZONE, clock, 400, true);
        assertThat(q.to()).isEqualTo(LocalDate.parse("2026-10-03"));
        assertThat(q.from()).isEqualTo(LocalDate.parse("2026-09-04"));
        AnalyticsQuery prev = QueryParser.parse(Map.of("from", "2026-09-01", "to", "2026-09-10", "compare", "previous"), AnalyticsTestSupport.ZONE,
                clock, 400, true);
        assertThat(prev.compareFrom()).isEqualTo(LocalDate.parse("2026-08-22"));
        assertThat(prev.compareTo()).isEqualTo(LocalDate.parse("2026-08-31"));
        assertThatThrownBy(() -> QueryParser.parse(Map.of("from", "2024-01-01"), AnalyticsTestSupport.ZONE, clock, 400, true))
                .isInstanceOf(AnalyticsException.class).extracting(e -> ((AnalyticsException) e).code()).isEqualTo("range_too_large");
        assertThatThrownBy(() -> QueryParser.parse(Map.of("from", "x"), AnalyticsTestSupport.ZONE, clock, 400, true))
                .isInstanceOf(AnalyticsException.class);
        AnalyticsQuery hours = QueryParser.parse(Map.of("from", "2026-06-01", "granularity", "hour"), AnalyticsTestSupport.ZONE, clock, 400, true);
        assertThat(hours.granularity()).isEqualTo("day");
        AnalyticsQuery partner = QueryParser.parse(Map.of("includeBots", "true", "path", "/ro/promotions/*", "size", "9999"),
                AnalyticsTestSupport.ZONE, clock, 400, false);
        assertThat(partner.includeBots()).isFalse();
        assertThat(partner.size()).isEqualTo(500);
        assertThat(partner.filters()).containsEntry("path", "/ro/promotions/*");
    }

    @Test
    void pathPrefixFilter() {
        AnalyticsQuery q = QueryParser.parse(Map.of("path", "/ro/promotions/*", "from", "2026-10-03", "to", "2026-10-03"), AnalyticsTestSupport.ZONE,
                clock, 400, true);
        assertThat(EventFilter.matches(q, AnalyticsTestSupport.event("page_view", "a", "b", Instant.parse("2026-10-03T08:00:00Z"))
                .setPath("/ro/promotions/lapte"))).isTrue();
        assertThat(EventFilter.matches(q, AnalyticsTestSupport.event("page_view", "a", "b", Instant.parse("2026-10-03T08:00:00Z"))
                .setPath("/ro/blogs/x"))).isFalse();
        assertThat(EventFilter.matches(q, AnalyticsTestSupport.event("page_view", "a", "b", Instant.parse("2026-10-02T08:00:00Z"))
                .setPath("/ro/promotions/lapte"))).isFalse();
    }

    @Test
    void scheduleComputesNextRunAndPeriod() {
        ZonedDateTime saturday = ZonedDateTime.of(2026, 10, 3, 12, 0, 0, 0, AnalyticsTestSupport.ZONE);
        assertThat(ReportSchedule.next("WEEKLY", saturday, LocalTime.of(7, 0)).toLocalDateTime().toString()).isEqualTo("2026-10-05T07:00");
        assertThat(ReportSchedule.next("MONTHLY", saturday, LocalTime.of(7, 0)).toLocalDateTime().toString()).isEqualTo("2026-11-01T07:00");
        LocalDate[] week = ReportSchedule.period("WEEKLY", LocalDate.parse("2026-10-05"));
        assertThat(week).containsExactly(LocalDate.parse("2026-09-28"), LocalDate.parse("2026-10-04"));
        LocalDate[] month = ReportSchedule.period("MONTHLY", LocalDate.parse("2026-11-01"));
        assertThat(month).containsExactly(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"));
    }

    @Test
    void impersonationTokenRoundTrip() {
        ImpersonationTokens tokens = new ImpersonationTokens("k", clock, 60_000L);
        ImpersonationTokens.Issued t = tokens.issue("8", "1");
        assertThat(tokens.verify(t.token(), "1")).isEqualTo("8");
        assertThat(tokens.verify(t.token(), "2")).isNull();
        assertThat(new ImpersonationTokens("other", clock, 60_000L).verify(t.token(), "1")).isNull();
        assertThatThrownBy(() -> tokens.issue("8.9", "1")).isInstanceOf(AnalyticsException.class);
    }
}
