package com.naqqa.analytics;

import com.naqqa.analytics.config.NaqqaAnalyticsProperties;
import com.naqqa.analytics.export.ExportService;
import com.naqqa.analytics.export.Table;
import com.naqqa.analytics.model.AnalyticsEvent;
import com.naqqa.analytics.query.AnalyticsDtos;
import com.naqqa.analytics.query.AnalyticsQuery;
import com.naqqa.analytics.query.AnalyticsQueryService;
import com.naqqa.analytics.query.ListEventSource;
import com.naqqa.analytics.web.AnalyticsAccess;
import com.naqqa.analytics.web.AnalyticsException;
import com.naqqa.analytics.web.ImpersonationTokens;
import com.naqqa.analytics.web.QueryParser;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.naqqa.analytics.AnalyticsTestSupport.auth;
import static com.naqqa.analytics.AnalyticsTestSupport.entityEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalyticsPartnerScopeTest {

    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    private final AnalyticsTestSupport.MutableClock clock = new AnalyticsTestSupport.MutableClock(NOW);
    private final NaqqaAnalyticsProperties.Permissions perms = new NaqqaAnalyticsProperties.Permissions();
    private final ImpersonationTokens tokens = new ImpersonationTokens("secret", clock, 3_600_000L);
    private final AnalyticsAccess access = new AnalyticsAccess(perms,
            AnalyticsTestSupport.scopes(Map.of("10", Set.of("A"), "20", Set.of("B"), "30", Set.of("A", "C"))), null, tokens);
    private final Authentication repA = auth("10", "analytics:view_own_company", "analytics:export");
    private final Authentication repB = auth("20", "analytics:view_own_company");
    private final Authentication multi = auth("30", "analytics:view_own_company");
    private final Authentication admin = auth("1", "analytics:view_all", "analytics:impersonate_partner", "analytics:export");
    private final Authentication moderator = auth("2", "analytics:view_all");

    private AnalyticsQuery parse(Map<String, String> params) {
        return QueryParser.parse(params, AnalyticsTestSupport.ZONE, clock, 400, false);
    }

    private List<AnalyticsEvent> events() {
        List<AnalyticsEvent> out = new ArrayList<>();
        Instant t = NOW.minusSeconds(3600);
        for (int i = 0; i < 6; i++) {
            out.add(entityEvent("item_view", "a" + i, "sa" + i, t, "PROMOTION", "1", "A").setProps(AnalyticsTestSupport.props("activeMs", 1000)));
            out.add(entityEvent("item_impression", "a" + i, "sa" + i, t, "PROMOTION", "1", "A"));
        }
        for (int i = 0; i < 9; i++) {
            out.add(entityEvent("item_view", "b" + i, "sb" + i, t, "PROMOTION", "2", "B"));
            out.add(entityEvent("item_click", "b" + i, "sb" + i, t, "PROMOTION", "2", "B"));
        }
        out.add(entityEvent("item_view", "c0", "sc0", t, "PROMOTION", "3", "C"));
        return out;
    }

    @Test
    void representativeCannotSelectAnotherCompany() {
        assertThatThrownBy(() -> access.partner(repA, parse(Map.of("companyId", "B")), "B", null))
                .isInstanceOf(AnalyticsException.class).extracting(e -> ((AnalyticsException) e).status()).isEqualTo(403);
        AnalyticsQuery own = access.partner(repA, parse(Map.of("companyId", "A")), "A", null);
        assertThat(own.companyIds()).containsExactly("A");
        AnalyticsQuery ignoredFilter = access.partner(repA, parse(Map.of("companyId", "B", "from", "2026-10-01")), null, null);
        assertThat(ignoredFilter.companyIds()).containsExactly("A");
        assertThat(ignoredFilter.filters()).doesNotContainKey("companyId");
        AnalyticsQuery both = access.partner(multi, parse(Map.of()), null, null);
        assertThat(both.companyIds()).containsExactlyInAnyOrder("A", "C");
        assertThatThrownBy(() -> access.partner(multi, parse(Map.of()), "B", null)).isInstanceOf(AnalyticsException.class);
    }

    @Test
    void accountsWithoutPermissionOrCompanyAreRejected() {
        assertThatThrownBy(() -> access.partner(auth("99", "analytics:view_own_company"), parse(Map.of()), null, null))
                .isInstanceOf(AnalyticsException.class);
        assertThatThrownBy(() -> access.partner(auth("10"), parse(Map.of()), null, null)).isInstanceOf(AnalyticsException.class);
        assertThatThrownBy(() -> access.admin(repA, parse(Map.of()))).isInstanceOf(AnalyticsException.class);
        assertThat(access.admin(moderator, parse(Map.of())).companyIds()).isNull();
        assertThatThrownBy(() -> access.admin(null, parse(Map.of()))).isInstanceOf(AnalyticsException.class)
                .extracting(e -> ((AnalyticsException) e).status()).isEqualTo(401);
    }

    @Test
    void partnerReportsAndExportsNeverLeakOtherCompanies() {
        AnalyticsQueryService queries = AnalyticsTestSupport.queries(new ListEventSource(events()), clock, null);
        AnalyticsQuery qa = access.partner(repA, parse(Map.of("companyId", "B", "entityId", "2")), null, null);
        AnalyticsDtos.PartnerItems items = queries.partnerItems(qa);
        assertThat(items.items()).isEmpty();
        AnalyticsQuery qa2 = access.partner(repA, parse(Map.of()), null, null);
        AnalyticsDtos.PartnerItems own = queries.partnerItems(qa2);
        assertThat(own.items()).extracting(AnalyticsDtos.ContentItem::companyId).containsOnly("A");
        assertThat(own.items().get(0).views()).isEqualTo(6);
        assertThat(own.items().get(0).impressions()).isEqualTo(6);
        AnalyticsDtos.PartnerOverview ov = queries.partnerOverview(qa2);
        assertThat(ov.kpis().views()).isEqualTo(6);
        assertThat(ov.kpis().clicks()).isZero();
        ExportService exports = new ExportService(queries);
        String report = ExportService.resolve("content", true);
        assertThat(report).isEqualTo("partner-items");
        List<Table> tables = exports.tables(report, qa2);
        for (Table t : tables) {
            for (List<Object> row : t.rows()) {
                assertThat(row.get(3)).isEqualTo("A");
            }
        }
        String csv = new String(exports.export(report, qa2, "csv"), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).doesNotContain(",B,").contains(",A,");
        assertThatThrownBy(() -> ExportService.resolve("companies", true)).isInstanceOf(AnalyticsException.class);
        assertThatThrownBy(() -> ExportService.resolve("quality", true)).isInstanceOf(AnalyticsException.class);
        AnalyticsDtos.Audience audience = queries.audience(qa2, true);
        assertThat(audience.devices()).allMatch(s -> s.insufficient() || s.visitors() >= 5);
        AnalyticsDtos.Dimensions dims = queries.dimensions(qa2, "entityId");
        assertThat(dims.values()).extracting(AnalyticsDtos.DimensionValue::value).containsExactly("1");
        assertThatThrownBy(() -> queries.dimensions(qa2, "companyId")).isInstanceOf(IllegalArgumentException.class);
        AnalyticsQuery qb = access.partner(repB, parse(Map.of()), null, null);
        assertThat(queries.partnerItems(qb).items()).extracting(AnalyticsDtos.ContentItem::companyId).containsOnly("B");
    }

    @Test
    void impersonationIsBoundToAdminAndExpires() {
        ImpersonationTokens.Issued issued = tokens.issue("B", "1");
        assertThat(access.partner(admin, parse(Map.of()), null, issued.token()).companyIds()).containsExactly("B");
        assertThatThrownBy(() -> access.partner(moderator, parse(Map.of()), null, issued.token())).isInstanceOf(AnalyticsException.class);
        assertThatThrownBy(() -> access.partner(auth("3", "analytics:impersonate_partner"), parse(Map.of()), null, issued.token()))
                .isInstanceOf(AnalyticsException.class);
        assertThatThrownBy(() -> access.partner(repA, parse(Map.of()), null, issued.token())).isInstanceOf(AnalyticsException.class);
        String forged = issued.token().replace("B.1.", "A.1.");
        assertThatThrownBy(() -> access.partner(admin, parse(Map.of()), null, forged)).isInstanceOf(AnalyticsException.class);
        clock.advance(3_600_001L);
        assertThatThrownBy(() -> access.partner(admin, parse(Map.of()), null, issued.token())).isInstanceOf(AnalyticsException.class);
    }

    @Test
    void partnerSearchesAndBenchmarkRespectThresholds() {
        List<AnalyticsEvent> e = new ArrayList<>();
        Instant t = NOW.minusSeconds(600);
        for (int i = 0; i < 6; i++) {
            e.add(AnalyticsTestSupport.event("search", "v" + i, "s" + i, t).setProps(AnalyticsTestSupport.props("q", "lapte", "results", 4)));
            e.add(entityEvent("search_result_click", "v" + i, "s" + i, t.plusSeconds(5), "PROMOTION", "1", "A")
                    .setProps(AnalyticsTestSupport.props("q", "lapte")));
        }
        for (int i = 0; i < 2; i++) {
            e.add(AnalyticsTestSupport.event("search", "w" + i, "w" + i, t).setProps(AnalyticsTestSupport.props("q", "rare", "results", 4)));
            e.add(entityEvent("search_result_click", "w" + i, "w" + i, t.plusSeconds(5), "PROMOTION", "1", "A")
                    .setProps(AnalyticsTestSupport.props("q", "rare")));
        }
        e.add(entityEvent("item_view", "v0", "s0", t, "PROMOTION", "1", "A").setCategoryId("4"));
        for (String company : List.of("B", "C", "D")) {
            e.add(entityEvent("item_view", "x" + company, "x" + company, t, "PROMOTION", company + "1", company).setCategoryId("4"));
        }
        AnalyticsQueryService queries = AnalyticsTestSupport.queries(new ListEventSource(e), clock, null);
        AnalyticsQuery qa = access.partner(repA, parse(Map.of()), null, null);
        AnalyticsDtos.PartnerSearches s = queries.partnerSearches(qa);
        assertThat(s.top()).containsExactly(new AnalyticsDtos.PartnerSearchRow("lapte", 6, 6));
        assertThat(s.suppressed()).isEqualTo(1);
        AnalyticsDtos.Benchmark b = queries.partnerBenchmark(qa);
        assertThat(b.categories()).hasSize(1);
        assertThat(b.categories().get(0).partners()).isEqualTo(4);
        assertThat(b.categories().get(0).insufficient()).isTrue();
        assertThat(b.categories().get(0).category()).isNull();
    }
}
