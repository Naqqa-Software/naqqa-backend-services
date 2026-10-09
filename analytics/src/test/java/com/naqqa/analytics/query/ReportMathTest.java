package com.naqqa.analytics.query;

import com.naqqa.analytics.model.AnalyticsEvent;
import com.naqqa.analytics.query.AnalyticsDtos.BookletRow;
import com.naqqa.analytics.query.AnalyticsDtos.ContentItem;
import com.naqqa.analytics.query.AnalyticsDtos.Seg;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportMathTest {

    private static AnalyticsEvent event(String name, String vid, Boolean newVisitor, Map<String, Object> props) {
        AnalyticsEvent e = new AnalyticsEvent();
        e.setName(name);
        e.setVid(vid);
        e.setSid("s-" + vid);
        e.setTs(Instant.parse("2026-10-09T10:00:00Z"));
        e.setNewVisitor(newVisitor);
        e.setEntityType("BOOKLET");
        e.setEntityId("15");
        e.setCompanyId("1");
        e.setProps(props);
        return e;
    }

    @Test
    void newVsReturningCountsEachVisitorOnceAndMatchesUniqueVisitors() {
        List<AnalyticsEvent> events = List.of(
                event("page_view", "a", null, Map.of()),
                event("page_view", "a", true, Map.of()),
                event("page_view", "b", false, Map.of()),
                event("page_view", "b", false, Map.of()),
                event("page_view", "c", null, Map.of()));

        List<Seg> segs = Insights.newVsReturning(events, 0);

        assertThat(segs).extracting(Seg::key, Seg::visitors).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("new", 1L),
                org.assertj.core.groups.Tuple.tuple("returning", 1L),
                org.assertj.core.groups.Tuple.tuple("unknown", 1L));
        assertThat(segs.stream().mapToLong(Seg::visitors).sum()).isEqualTo(Reports.visitors(events));
        assertThat(segs.stream().mapToDouble(Seg::share).sum()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void bookletItemActiveTimeComesFromPageViews() {
        List<AnalyticsEvent> events = List.of(
                event("booklet_open", "a", true, Map.of("pages", 12)),
                event("booklet_page_view", "a", true, Map.of("page", 1, "activeMs", 3291)),
                event("booklet_page_view", "a", true, Map.of("page", 3, "activeMs", 2671)),
                event("booklet_page_view", "a", true, Map.of("page", 5, "activeMs", 35769)),
                event("booklet_complete", "a", true, Map.of("pct", 50, "pages", 12)));

        ContentItem item = Reports.items(events, List.of(), null, null).get(0);
        assertThat(item.views()).isEqualTo(1);
        assertThat(item.uniques()).isEqualTo(1);
        assertThat(item.avgActiveMs()).isEqualTo(3291 + 2671 + 35769);

        BookletRow row = Reports.booklets(events, Map.of()).get(0);
        assertThat(row.opens()).isEqualTo(1);
        assertThat(row.pageViews()).isEqualTo(3);
        assertThat(row.avgPagesPerOpen()).isEqualTo(3.0);
        assertThat(row.avgCompletionPct()).isEqualTo(50.0);
        assertThat(row.avgPageActiveMs()).isEqualTo(13910);
        assertThat(row.pages()).hasSize(3);
    }
}
