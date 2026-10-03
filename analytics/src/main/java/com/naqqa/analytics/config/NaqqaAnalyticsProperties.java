package com.naqqa.analytics.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@ConfigurationProperties("naqqa.analytics")
public class NaqqaAnalyticsProperties {

    private boolean enabled = true;
    private String collectPath = "/t/e";
    private String apiPath = "/api/analytics";
    private String timezone = "Europe/Chisinau";
    private String secret = "";
    private int rawRetentionDays = 400;
    private String visitorSalt = "";
    private boolean storeRawIp = false;
    private int sessionTimeoutMinutes = 30;
    private String rollupCron = "0 20 2 * * *";
    private int realtimeWindowMinutes = 30;
    private String pixelPath = "/t/p.gif";
    private String scriptPath = "/t/tracker.js";
    private boolean scriptEnabled = true;
    private boolean trackViewEnabled = true;
    private int kAnonymity = 5;
    private int benchmarkMinPartners = 5;
    private List<String> testTitlePatterns = new ArrayList<>(List.of("(?i)\\btest\\b", "(?i)\\bqa\\b", "(?i)final qa", "(?i)\\bdemo\\b", "(?i)\\bdummy\\b", "(?i)lorem ipsum"));
    private List<String> ownHosts = new ArrayList<>();
    private Collections collections = new Collections();
    private Permissions permissions = new Permissions();
    private Limits limits = new Limits();
    private RateLimit rateLimit = new RateLimit();
    private Session session = new Session();
    private Writer writer = new Writer();
    private Bots bots = new Bots();
    private Geo geo = new Geo();
    private Rollup rollup = new Rollup();
    private Query query = new Query();
    private Realtime realtime = new Realtime();
    private Reports reports = new Reports();
    private Redis redis = new Redis();
    private Map<String, String> sourceOverrides = new LinkedHashMap<>();

    @Data
    public static class Collections {
        private String event = "an_event";
        private String session = "an_session";
        private String visitor = "an_visitor";
        private String daily = "an_daily";
        private String audit = "an_audit";
        private String savedView = "an_saved_view";
        private String scheduledReport = "an_scheduled_report";
        private String quality = "an_quality";
        private boolean createIndexes = true;
    }

    @Data
    public static class Permissions {
        private String viewAll = "analytics:view_all";
        private String viewOwnCompany = "analytics:view_own_company";
        private String export = "analytics:export";
        private String realtime = "analytics:realtime";
        private String impersonatePartner = "analytics:impersonate_partner";
        private String chatViewAll = "chat_analytics:view_all";
        private String chatViewOwn = "chat_analytics:view_own";
        private String bannersManage = "banners:manage";
        private String bannersPropose = "banners:propose";
        private String bannersApprove = "banners:approve";
    }

    @Data
    public static class Limits {
        private int maxBodyBytes = 65_536;
        private int maxEvents = 50;
        private int maxProps = 24;
        private int maxStringLength = 200;
        private int maxUrlLength = 500;
        private int maxPathLength = 300;
        private long maxFutureSkewMs = 600_000L;
        private long maxPastMs = 259_200_000L;
    }

    @Data
    public static class RateLimit {
        private boolean enabled = true;
        private int requestsPerMinute = 120;
        private int eventsPerMinute = 1_500;
    }

    @Data
    public static class Session {
        private int cacheSize = 50_000;
    }

    @Data
    public static class Writer {
        private int queueCapacity = 50_000;
        private int batchSize = 500;
        private long flushIntervalMs = 1_000L;
        private String backlogKey = "naqqa:an:backlog";
        private int backlogMax = 1_000_000;
    }

    @Data
    public static class Bots {
        private List<String> extraUserAgentMarkers = new ArrayList<>();
        private int fastMinPageViews = 5;
        private long minPageViewIntervalMs = 300L;
        private int noInteractionPageViews = 30;
        private boolean trustProxyHeaders = true;
    }

    @Data
    public static class Geo {
        private String databasePath = "";
        private boolean fallbackEnabled = false;
        private String fallbackUrl = "https://ipapi.co/{ip}/json/";
        private long fallbackTimeoutMs = 1_500L;
        private int cacheSize = 10_000;
        private long cacheTtlMinutes = 1_440L;
    }

    @Data
    public static class Rollup {
        private boolean enabled = true;
        private long todayIntervalMs = 600_000L;
    }

    @Data
    public static class Query {
        private int cacheSeconds = 60;
        private int maxRangeDays = 400;
        private long maxScanEvents = 3_000_000L;
        private int topLimit = 50;
        private boolean useRollups = true;
    }

    @Data
    public static class Realtime {
        private long pushIntervalMs = 5_000L;
        private long emitterTimeoutMs = 1_800_000L;
        private int maxEmitters = 200;
    }

    @Data
    public static class Reports {
        private boolean enabled = true;
        private String cron = "0 0 7 * * *";
        private int maxPerUser = 20;
        private int maxRecipients = 10;
        private long impersonationTtlMinutes = 60;
    }

    @Data
    public static class Redis {
        private String prefix = "naqqa:an:";
    }
}
