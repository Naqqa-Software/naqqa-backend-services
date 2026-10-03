package com.naqqa.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.analytics.collect.BotDetector;
import com.naqqa.analytics.collect.ChannelClassifier;
import com.naqqa.analytics.collect.CollectorService;
import com.naqqa.analytics.collect.CookielessHasher;
import com.naqqa.analytics.collect.EntityLookup;
import com.naqqa.analytics.collect.MemoryKeyValueStore;
import com.naqqa.analytics.collect.QualityCounters;
import com.naqqa.analytics.collect.RateLimiter;
import com.naqqa.analytics.collect.RealtimeCounters;
import com.naqqa.analytics.collect.SessionCache;
import com.naqqa.analytics.collect.SessionState;
import com.naqqa.analytics.collect.Sessionizer;
import com.naqqa.analytics.config.NaqqaAnalyticsProperties;
import com.naqqa.analytics.model.AnalyticsEvent;
import com.naqqa.analytics.query.AnalyticsQueryService;
import com.naqqa.analytics.query.EventSource;
import com.naqqa.analytics.query.ListEventSource;
import com.naqqa.analytics.registry.EventRegistry;
import com.naqqa.analytics.registry.RegistryValidator;
import com.naqqa.analytics.spi.AnalyticsEntityResolver;
import com.naqqa.analytics.spi.AnalyticsGeoResolver;
import com.naqqa.analytics.spi.AnalyticsPrincipalResolver;
import com.naqqa.analytics.spi.AnalyticsScopeResolver;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public final class AnalyticsTestSupport {

    public static final ZoneId ZONE = ZoneId.of("Europe/Chisinau");
    public static final EventRegistry REGISTRY = EventRegistry.classpath();
    public static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private AnalyticsTestSupport() {
    }

    public static final class MutableClock extends Clock {
        private final AtomicLong now;

        public MutableClock(Instant start) {
            this.now = new AtomicLong(start.toEpochMilli());
        }

        public void advance(long ms) {
            now.addAndGet(ms);
        }

        public void set(Instant t) {
            now.set(t.toEpochMilli());
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            MutableClock self = this;
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return zone;
                }

                @Override
                public Clock withZone(ZoneId z) {
                    return self.withZone(z);
                }

                @Override
                public Instant instant() {
                    return self.instant();
                }
            };
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now.get());
        }
    }

    public static RegistryValidator validator() {
        return new RegistryValidator(REGISTRY, 24, 200, 500);
    }

    public static Authentication auth(String userId, String... authorities) {
        List<GrantedAuthority> list = new ArrayList<>();
        for (String a : authorities) {
            list.add(new SimpleGrantedAuthority(a));
        }
        return new UsernamePasswordAuthenticationToken(userId, null, list);
    }

    public static final AnalyticsPrincipalResolver STAFF_IS_INTERNAL = new AnalyticsPrincipalResolver() {
        @Override
        public boolean isInternal(Authentication authentication) {
            if (!AnalyticsPrincipalResolver.authenticated(authentication)) {
                return false;
            }
            for (GrantedAuthority a : authentication.getAuthorities()) {
                if (Set.of("ROLE_ADMIN", "ROLE_MODERATOR", "ROLE_REPRESENTATIVE").contains(a.getAuthority())) {
                    return true;
                }
            }
            return false;
        }
    };

    public static AnalyticsScopeResolver scopes(Map<String, Set<String>> byUser) {
        return new AnalyticsScopeResolver() {
            @Override
            public Set<String> companyIds(Authentication authentication) {
                return authentication == null ? Set.of() : byUser.getOrDefault(authentication.getName(), Set.of());
            }

            @Override
            public Set<String> companyIdsForUser(String userId) {
                return byUser.getOrDefault(userId, Set.of());
            }
        };
    }

    public static final class Harness {
        public final MutableClock clock;
        public final NaqqaAnalyticsProperties properties = new NaqqaAnalyticsProperties();
        public final List<AnalyticsEvent> events = new ArrayList<>();
        public final List<SessionState> sessions = new ArrayList<>();
        public final Set<String> knownVisitors = new HashSet<>();
        public final QualityCounters quality = new QualityCounters();
        public final MemoryKeyValueStore kv;
        public final CollectorService collector;
        public final Map<String, AnalyticsEntityResolver.EntityInfo> entities = new LinkedHashMap<>();

        public Harness(Instant start) {
            this(start, AnalyticsPrincipalResolver.NONE);
        }

        public Harness(Instant start, AnalyticsPrincipalResolver principals) {
            clock = new MutableClock(start);
            kv = new MemoryKeyValueStore(clock);
            properties.setOwnHosts(List.of("omy.md"));
            AnalyticsEntityResolver resolver = new AnalyticsEntityResolver() {
                @Override
                public EntityInfo resolve(String entityType, String entityId) {
                    return entities.get(entityType + ":" + entityId);
                }
            };
            EntityLookup lookup = new EntityLookup(resolver, properties.getTestTitlePatterns(), 0, 10_000, clock);
            collector = new CollectorService(properties, validator(), BotDetector.defaults(), new ChannelClassifier(properties.getOwnHosts()),
                    new Sessionizer(30 * 60_000L, ZONE), new CookielessHasher(day -> "salt-" + day, "pepper"),
                    new RateLimiter(kv, "t:", properties.getRateLimit().getRequestsPerMinute(), properties.getRateLimit().getEventsPerMinute(), clock),
                    new SessionCache(10_000, null, knownVisitors::add), events::addAll, sessions::add, lookup, AnalyticsGeoResolver.NONE, principals,
                    quality, new RealtimeCounters(kv, "t:"), MAPPER, clock);
        }

        public CollectorService.CollectResult send(String json) {
            return send(json, "93.115.10.25", CHROME, null);
        }

        public CollectorService.CollectResult send(String json, String ip, String ua, Authentication auth) {
            return collector.collect(new CollectorService.CollectRequest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8), ip, ua, auth, "omy.md"));
        }

        public long now() {
            return clock.millis();
        }
    }

    public static final String CHROME = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36";
    public static final String IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";

    public static String envelope(String vid, String sid, String consent, String ctx, String... events) {
        return "{\"v\":1,\"vid\":" + (vid == null ? "null" : "\"" + vid + "\"") + ",\"sid\":\"" + sid + "\",\"uid\":null,\"consent\":\"" + consent
                + "\",\"ctx\":" + (ctx == null ? "{\"lang\":\"ro\",\"path\":\"/ro/\",\"pageType\":\"home\"}" : ctx) + ",\"events\":["
                + String.join(",", events) + "]}";
    }

    public static String ev(String name, long t, String props) {
        return "{\"id\":\"" + java.util.UUID.randomUUID() + "\",\"t\":" + t + ",\"name\":\"" + name + "\",\"props\":" + (props == null ? "{}" : props) + "}";
    }

    public static AnalyticsEvent event(String name, String vid, String sid, Instant ts) {
        return new AnalyticsEvent().setId(java.util.UUID.randomUUID().toString()).setName(name).setVid(vid).setSid(sid).setTs(ts)
                .setDay(ts.atZone(ZONE).toLocalDate().toString());
    }

    public static AnalyticsEvent entityEvent(String name, String vid, String sid, Instant ts, String type, String id, String company) {
        return event(name, vid, sid, ts).setEntityType(type).setEntityId(id).setCompanyId(company);
    }

    public static Map<String, Object> props(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    public static AnalyticsQueryService queries(Collection<AnalyticsEvent> events, Clock clock) {
        return queries(new ListEventSource(events), clock, AnalyticsScopeResolver.NONE);
    }

    public static AnalyticsQueryService queries(EventSource source, Clock clock, AnalyticsScopeResolver scopes) {
        AnalyticsQueryService s = new AnalyticsQueryService(source, null, scopes, null, null, null, null, null, clock, 5, 5, 50, 30, false);
        s.setZone(ZONE.getId());
        return s;
    }

    public static <T> List<T> listOf(T... values) {
        return new ArrayList<>(Arrays.asList(values));
    }
}
