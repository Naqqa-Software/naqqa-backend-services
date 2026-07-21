# naqqa-seo-farm

Autonomous SEO blog generation as a Spring Boot library: SerpAPI keyword research (multi-key
failover, quota-aware), competitor analysis (jsoup SERP scraping), Claude authoring with per-site
voice profiles, Pexels imagery, Markdown→HTML + schema.org build, and sitemap generation. Blogs are
stored in the host's MongoDB; a daily scheduler keeps each active site fresh.

## Install

```xml
<dependency>
  <groupId>com.naqqa</groupId>
  <artifactId>naqqa-seo-farm</artifactId>
  <version>1.0.0</version>
</dependency>
<!-- Transitive deps if your Docker build uses a stub pom: -->
<dependency><groupId>com.vladsch.flexmark</groupId><artifactId>flexmark-all</artifactId><version>0.64.8</version></dependency>
<dependency><groupId>org.jsoup</groupId><artifactId>jsoup</artifactId><version>1.17.2</version></dependency>
```

## Wire the host

```java
@SpringBootApplication(scanBasePackages = { "com.myapp", "com.naqqa.seofarm" })
@EnableScheduling   // for the daily generation scheduler
public class MyApp { ... }
```

Auto-configures its own `@EnableMongoRepositories("com.naqqa.seofarm.repository")` and
`SeoFarmProperties` (`naqqa.seofarm.*`). Claude is called through the **host's** `anthropic.api.key /
base-url / version` properties (resolved from the host environment).

## Config (`naqqa.seofarm.*`)

```properties
naqqa.seofarm.serp-api-key=${SEOFARM_SERPAPI_KEY:}      # comma-separated for multi-account failover
naqqa.seofarm.pexels-api-key=${SEOFARM_PEXELS_KEY:}
naqqa.seofarm.model=claude-sonnet-4-6
naqqa.seofarm.schedule-enabled=${SEOFARM_SCHEDULE_ENABLED:true}
naqqa.seofarm.schedule-cron=${SEOFARM_SCHEDULE_CRON:0 0 21 * * *}
naqqa.seofarm.daily-limit-per-site=1
naqqa.seofarm.keyword-refresh-days=7
naqqa.seofarm.max-root-terms-per-extraction=12          # caps SerpAPI cost per run
naqqa.seofarm.analytics-base-url=${SEOFARM_ANALYTICS_BASE_URL:}  # injects naqqa-analytics beacon into blog HTML
```

## What the host owns

The library exposes services (`BlogGenerationService`, `SeoBlogService`, `SeoPagesService`,
`SeoStatsService`, `SeoSitemapService`, …). The host provides thin controllers that add security
(`@PreAuthorize("hasAuthority('seofarm:*')")`) and the public `/api/seo-blogs/**` + sitemap endpoints.
Target sites are bundled in `seofarm/sites.json`; per-site voice profiles in `seofarm/profiles/<id>/`.

## Data

Collections: `seo_blogs`, `seo_generation_logs`, `seo_keyword_batches`, `seo_site_state`,
`seo_used_keywords`, `seo_used_images`. Extraction is capped and quota-aware; blogs are unique by slug.
