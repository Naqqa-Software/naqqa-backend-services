# naqqa-analytics

Privacy-aware, **entity-agnostic** web analytics as a Spring Boot library — page views, unique
users (new vs returning), sessions, bounce rate, time-on-page, geo (country + city), device / OS /
browser, traffic sources & referrers, UTM campaigns, languages, top / landing pages, custom events
and a **realtime** stream. Track blogs today, courses (or anything with an id) tomorrow — no schema
changes, just a different `entityType`.

Everything a small Google-Analytics-style dashboard needs, self-hosted, in your own MongoDB.

---

## 1. Install

The library is a plain Spring Boot auto-configured jar (`com.naqqa:naqqa-analytics`). Add it to the
host `pom.xml`:

```xml
<dependency>
  <groupId>com.naqqa</groupId>
  <artifactId>naqqa-analytics</artifactId>
  <version>1.0.0</version>
</dependency>
<!-- Only if your Docker build installs the jar with a stub pom (transitive deps not resolved): -->
<dependency>
  <groupId>com.maxmind.geoip2</groupId>
  <artifactId>geoip2</artifactId>
  <version>4.2.0</version>
</dependency>
```

## 2. Wire the host (once)

```java
@SpringBootApplication(scanBasePackages = { "com.myapp", "com.naqqa.analytics" }) // 1. component-scan
@EnableScheduling  // 2. rollup + realtime push schedulers
public class MyApp { ... }
```

The library auto-configures its own `@EnableMongoRepositories("com.naqqa.analytics.repository")`, so
its collections live in your existing Mongo with no extra config.

Config (`application.properties`, all optional except geo if you want country/city):

```properties
naqqa.analytics.enabled=${ANALYTICS_ENABLED:true}
naqqa.analytics.geo-db-path=${ANALYTICS_GEO_DB_PATH:}          # MaxMind GeoLite2-City.mmdb; empty = geo off
naqqa.analytics.visitor-salt=${ANALYTICS_VISITOR_SALT:change-me} # stable! hashes fallback visitor identity
naqqa.analytics.store-raw-ip=${ANALYTICS_STORE_RAW_IP:false}
naqqa.analytics.session-timeout-minutes=${ANALYTICS_SESSION_TIMEOUT_MIN:30}
naqqa.analytics.rollup-cron=${ANALYTICS_ROLLUP_CRON:0 20 0 * * *}
naqqa.analytics.raw-retention-days=${ANALYTICS_RAW_RETENTION_DAYS:400}
naqqa.analytics.realtime-window-minutes=${ANALYTICS_REALTIME_WINDOW_MIN:30}
```

**Security:** the read/query API is guarded by the authority `analytics:read` (declare it in your
authority provider; admins get it). The ingest endpoints are public — permit them:

```java
auth.requestMatchers("/api/public/analytics/**").permitAll();
```

## 3. Track — three ways

Pick per surface. All three feed the same enriched pipeline (IP→geo, UA→device, referrer→source).

### a) Frontend snippet (any site, drop-in)

```html
<script defer src="https://YOUR-BACKEND/api/public/analytics/tracker.js"
        data-property="mysite.com" data-entity-type="blog"></script>
```

Optional attributes: `data-entity-id` (else the URL path), `data-endpoint` (override backend).
It handles a first-party client id (cookie + localStorage), sessions, time-on-page and `sendBeacon`.

SPA route changes & custom events:

```js
window.naqqaAnalytics.track({ entityType: 'blog', entityId: slug, title: document.title });
window.naqqaAnalytics.event('cta_click', { entityId: slug });
```

### b) Zero-code backend annotation

```java
@GetMapping("/api/courses/{id}")
@TrackView(entityType = "course", entityIdParam = "id", propertyParam = "site")
public Course get(@PathVariable String id, @RequestParam String site) { ... }
```

A view is recorded automatically after a 2xx response.

### c) One-line backend facade

```java
@Autowired AnalyticsTracker analytics;

@GetMapping("/api/blogs/slug/{slug}")
public Blog get(@PathVariable String slug, HttpServletRequest req) {
    analytics.trackView("mysite.com", "blog", slug, req);   // done
    return service.bySlug(slug);
}
```

> Tip: combine (a) + (b/c). The frontend beacon captures real-visitor device/referrer/time-on-page;
> the server-side call is a reliable fallback that fires even if JS is blocked.

## 4. Read / query API (`analytics:read`)

| Endpoint | Purpose |
|---|---|
| `GET /api/analytics/overview` | KPIs + timeseries + all breakdowns in one payload |
| `GET /api/analytics/timeseries` | views/visitors/sessions over time |
| `GET /api/analytics/top-entities` | top blogs/courses/… |
| `GET /api/analytics/events` | custom-event report |
| `GET /api/analytics/realtime` | last-N-minutes snapshot |
| `GET /api/analytics/realtime/stream` | **SSE** live snapshots (event `realtime`) |
| `GET /api/analytics/properties` / `entity-types` | filter discovery |

Common params: `property` (required), `entityType`, `entityId` (drill-down), `from`, `to`
(ISO or `yyyy-MM-dd`), `granularity` (`HOUR|DAY|WEEK|MONTH`).

`overview` returns: `kpis` (views, visitors, newUsers, returningUsers, sessions, engagedViews,
avgDurationMs, avgSessionDurationMs, viewsPerSession, engagementRate, bounceRate, entities),
`series`, and `byCountry / byDevice / byBrowser / byOs / bySource / byReferrer / byLanguage /
topPages / landingPages / newVsReturning / topEntities`.

## 5. Geo (MaxMind)

Sign up free at maxmind.com, download **GeoLite2-City.mmdb**, mount it and set
`ANALYTICS_GEO_DB_PATH`. Absent file → geo silently skipped (everything else works). Behind a proxy,
ensure `X-Forwarded-For` is set (nginx: `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;`).

## 6. Privacy

Visitor identity is a **non-reversible hash / first-party random id** — raw IP is not stored unless
`store-raw-ip=true`. Honour consent on the client: only load the snippet / call the tracker after the
user accepts cookies (see the Angular example below).

## 7. Data model

- `analytics_events` — raw enriched hits (pageview / engagement / event).
- `analytics_daily` — nightly per-(property, entityType, entityId) rollups; raw pruned after
  `raw-retention-days`, rollups kept.

## 8. Frontend example (Angular SSR, consent-gated)

See `naqqa-seo-1`'s `core/analytics/analytics.service.ts` for a full reference: browser-only, fires a
pageview on every `NavigationEnd`, derives `entityType`/`entityId` from the route, tracks
time-on-page, and only sends when `localStorage['naqqa_cookie_consent'] === 'accepted'`.
