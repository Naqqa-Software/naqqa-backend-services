package com.naqqa.analytics.collect;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.analytics.collect.ChannelClassifier.Attribution;
import com.naqqa.analytics.config.NaqqaAnalyticsProperties;
import com.naqqa.analytics.model.AnalyticsEvent;
import com.naqqa.analytics.registry.PiiScrubber;
import com.naqqa.analytics.registry.RegistryValidator;
import com.naqqa.analytics.spi.AnalyticsEntityResolver.EntityInfo;
import com.naqqa.analytics.spi.AnalyticsGeoResolver;
import com.naqqa.analytics.spi.AnalyticsPrincipalResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@Slf4j
public class CollectorService {

    public static final String FULL = "full";
    public static final String COOKIELESS = "cookieless";

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_.:-]{6,64}$");
    private static final Pattern LANG = Pattern.compile("^[a-z]{2}$");
    private static final Pattern SIZE = Pattern.compile("^\\d{1,5}x\\d{1,5}$");
    private static final Pattern PAGE_TYPE = Pattern.compile("^[a-z_]{1,30}$");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final int LOCKS = 64;

    private final NaqqaAnalyticsProperties properties;
    private final RegistryValidator validator;
    private final BotDetector bots;
    private final ChannelClassifier channels;
    private final Sessionizer sessionizer;
    private final CookielessHasher hasher;
    private final RateLimiter rateLimiter;
    private final SessionCache sessions;
    private final Consumer<List<AnalyticsEvent>> sink;
    private final Consumer<SessionState> sessionSink;
    private final EntityLookup entities;
    private final AnalyticsGeoResolver geo;
    private final AnalyticsPrincipalResolver principals;
    private final QualityCounters quality;
    private final RealtimeCounters realtime;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Object[] locks = new Object[LOCKS];

    public CollectorService(NaqqaAnalyticsProperties properties, RegistryValidator validator, BotDetector bots, ChannelClassifier channels,
                            Sessionizer sessionizer, CookielessHasher hasher, RateLimiter rateLimiter, SessionCache sessions,
                            Consumer<List<AnalyticsEvent>> sink, Consumer<SessionState> sessionSink, EntityLookup entities,
                            AnalyticsGeoResolver geo, AnalyticsPrincipalResolver principals, QualityCounters quality,
                            RealtimeCounters realtime, ObjectMapper mapper, Clock clock) {
        this.properties = properties;
        this.validator = validator;
        this.bots = bots;
        this.channels = channels;
        this.sessionizer = sessionizer;
        this.hasher = hasher;
        this.rateLimiter = rateLimiter;
        this.sessions = sessions;
        this.sink = sink;
        this.sessionSink = sessionSink;
        this.entities = entities;
        this.geo = geo == null ? AnalyticsGeoResolver.NONE : geo;
        this.principals = principals == null ? AnalyticsPrincipalResolver.NONE : principals;
        this.quality = quality;
        this.realtime = realtime;
        this.mapper = mapper;
        this.clock = clock;
        for (int i = 0; i < LOCKS; i++) {
            locks[i] = new Object();
        }
    }

    public Sessionizer sessionizer() {
        return sessionizer;
    }

    public CollectResult collect(CollectRequest request) {
        byte[] body = request.body();
        NaqqaAnalyticsProperties.Limits limits = properties.getLimits();
        if (body == null || body.length == 0) {
            quality.reject("empty_body", 1);
            return CollectResult.status(400, "bad_request");
        }
        if (body.length > limits.getMaxBodyBytes()) {
            quality.reject("too_large", 1);
            return CollectResult.status(413, "payload_too_large");
        }
        Map<String, Object> envelope;
        try {
            envelope = mapper.readValue(body, MAP);
        } catch (Exception e) {
            quality.reject("bad_json", 1);
            return CollectResult.status(400, "bad_request");
        }
        return process(envelope, request, true);
    }

    public CollectResult process(Map<String, Object> envelope, CollectRequest request, boolean rateLimit) {
        NaqqaAnalyticsProperties.Limits limits = properties.getLimits();
        if (envelope == null || !(envelope.get("events") instanceof List<?> rawEvents)) {
            quality.reject("bad_envelope", 1);
            return CollectResult.status(400, "bad_request");
        }
        if (rateLimit && properties.getRateLimit().isEnabled()) {
            RateLimiter.Decision decision = rateLimiter.check(request.ip(), rawEvents.size());
            if (!decision.allowed()) {
                quality.reject(QualityCounters.RATE_LIMITED, rawEvents.size());
                return new CollectResult(429, "rate_limited", 0, rawEvents.size(), null, decision.retryAfterSeconds());
            }
        }
        Object version = envelope.get("v");
        if (version != null && !(version instanceof Number n && n.intValue() == 1)) {
            quality.reject("bad_version", rawEvents.size());
            return CollectResult.status(400, "bad_request");
        }
        String clientSid = safeId(envelope.get("sid"));
        if (clientSid == null) {
            quality.reject("bad_sid", rawEvents.size());
            return CollectResult.status(400, "bad_request");
        }
        String consent = FULL.equals(envelope.get("consent")) ? FULL : COOKIELESS;
        Context ctx = context(envelope.get("ctx"));
        Identity identity = identity(request, consent, safeId(envelope.get("vid")), ctx);
        List<Raw> raws = new ArrayList<>();
        int rejected = 0;
        int index = 0;
        long now = clock.millis();
        for (Object o : rawEvents) {
            if (index++ >= limits.getMaxEvents()) {
                rejected++;
                quality.reject("too_many_events", 1);
                continue;
            }
            Raw raw = raw(o, now, limits);
            if (raw.reason != null) {
                rejected++;
                quality.reject(raw.reason, 1);
                continue;
            }
            raws.add(raw);
        }
        raws.sort(Comparator.comparingLong(r -> r.ts));
        List<Long> pageViews = new ArrayList<>();
        for (Raw r : raws) {
            if ("page_view".equals(r.result.name())) {
                pageViews.add(r.ts);
            }
        }
        boolean tooFast = bots.tooFast(pageViews);
        Attribution attribution = channels.classify(new ChannelClassifier.Input(ctx.ref, ctx.utmSource, ctx.utmMedium, ctx.utmCampaign,
                ctx.click, ctx.refParam, ctx.app));
        List<AnalyticsEvent> out = new ArrayList<>(raws.size());
        String sid;
        synchronized (lock(clientSid)) {
            SessionState state = sessions.get(clientSid);
            for (Raw r : raws) {
                String path = r.path != null ? r.path : ctx.path;
                state = advance(state, clientSid, r.ts, attribution, path, identity, ctx, consent);
                String bot = identity.botReason;
                if (bot == null && tooFast) {
                    bot = BotDetector.TOO_FAST;
                }
                Long activeMs = r.result.props().get("activeMs") instanceof Number n ? n.longValue() : null;
                if ("page_view".equals(r.result.name())) {
                    state.pageViews(state.pageViews() + 1);
                }
                if (BotDetector.isInteraction(r.result.name(), activeMs)) {
                    state.interactions(state.interactions() + 1);
                }
                if ("page_leave".equals(r.result.name()) && activeMs != null) {
                    state.activeMs(state.activeMs() + activeMs);
                }
                if (bot == null && bots.noInteraction(state.pageViews(), state.interactions())) {
                    bot = BotDetector.NO_INTERACTION;
                }
                if (bot != null && !state.bot()) {
                    state.bot(true).botReason(bot);
                }
                state.lastMs(Math.max(state.lastMs(), r.ts)).exit(path).dirty(true);
                out.add(build(r, state, identity, ctx, consent, path, now));
            }
            if (state == null) {
                state = sessions.get(clientSid);
            }
            sid = state == null ? clientSid : state.sid();
            if (state != null && state.dirty()) {
                sessions.put(clientSid, state);
                state.dirty(false);
                sessionSink.accept(state);
            }
        }
        if (!out.isEmpty()) {
            sink.accept(out);
            if (realtime != null) {
                realtime.record(out);
            }
        }
        return new CollectResult(202, null, out.size(), rejected, sid, 0);
    }

    public CollectResult trackCookieless(String name, Map<String, Object> props, String path, String pageType, String referrer, String lang,
                                         CollectRequest request, boolean rateLimit) {
        String day = sessionizer.day(clock.millis());
        String sid = "px" + CookielessHasher.sha256(properties.getVisitorSalt() + "|" + day + "|" + request.userAgent() + "|"
                + IpAnonymizer.truncate(request.ip()) + "|" + request.host()).substring(0, 30);
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("path", path);
        ctx.put("pageType", pageType);
        ctx.put("ref", referrer);
        ctx.put("lang", lang);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("name", name);
        event.put("t", clock.millis());
        event.put("props", props == null ? Map.of() : props);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("v", 1);
        envelope.put("sid", sid);
        envelope.put("consent", COOKIELESS);
        envelope.put("ctx", ctx);
        envelope.put("events", List.of(event));
        return process(envelope, request, rateLimit);
    }

    private SessionState advance(SessionState state, String clientSid, long ts, Attribution attribution, String path, Identity identity,
                                 Context ctx, String consent) {
        Sessionizer.Decision decision = sessionizer.decide(state, ts, attribution);
        if (!decision.newSession()) {
            if (identity.internal && !state.internal()) {
                state.internal(true);
            }
            if (identity.loggedIn && !state.loggedIn()) {
                state.loggedIn(true);
            }
            return state;
        }
        Attribution a = attribution;
        if (state != null && Sessionizer.MIDNIGHT.equals(decision.reason()) && !attribution.triggersNewSession() && state.attribution() != null) {
            a = state.attribution();
        }
        Boolean newVisitor = FULL.equals(consent) ? sessions.newVisitor(identity.vid, ts) : null;
        Map<String, String> utm = new LinkedHashMap<>();
        putIf(utm, "source", ctx.utmSource);
        putIf(utm, "medium", ctx.utmMedium);
        putIf(utm, "campaign", ctx.utmCampaign);
        putIf(utm, "term", ctx.utmTerm);
        putIf(utm, "content", ctx.utmContent);
        return new SessionState()
                .sid(Sessionizer.nextSid(clientSid, state, ts))
                .clientSid(clientSid)
                .vid(identity.vid)
                .startMs(ts)
                .lastMs(ts)
                .day(sessionizer.day(ts))
                .campaignKey(a.key())
                .attribution(a)
                .landing(path)
                .referrer(channels.externalHost(ctx.ref))
                .exit(path)
                .newVisitor(newVisitor)
                .device(identity.device)
                .os(identity.os)
                .browser(identity.browser)
                .lang(ctx.lang)
                .country(identity.country)
                .region(identity.region)
                .city(identity.city)
                .utm(utm.isEmpty() ? null : utm)
                .internal(identity.internal)
                .loggedIn(identity.loggedIn)
                .bot(identity.botReason != null)
                .botReason(identity.botReason)
                .dirty(true);
    }

    private AnalyticsEvent build(Raw r, SessionState s, Identity identity, Context ctx, String consent, String path, long now) {
        Map<String, Object> common = r.result.common();
        AnalyticsEvent e = new AnalyticsEvent()
                .setId(r.id)
                .setTs(Instant.ofEpochMilli(r.ts))
                .setRcv(Instant.ofEpochMilli(now))
                .setDay(sessionizer.day(r.ts))
                .setName(r.result.name())
                .setVid(identity.vid)
                .setSid(s.sid())
                .setUid(identity.uid)
                .setConsent(consent)
                .setLang(ctx.lang)
                .setPath(path)
                .setPageType(r.pageType != null ? r.pageType : ctx.pageType)
                .setLanding(s.landing())
                .setReferrer(s.referrer())
                .setIp(identity.rawIp)
                .setDevice(identity.device)
                .setOs(identity.os)
                .setBrowser(identity.browser)
                .setCountry(identity.country)
                .setRegion(identity.region)
                .setCity(identity.city)
                .setEntityType(str(common.get("entityType")))
                .setEntityId(str(common.get("entityId")))
                .setCompanyId(str(common.get("companyId")))
                .setCategoryId(str(common.get("categoryId")))
                .setSponsored(common.get("sponsored") instanceof Boolean b ? b : null)
                .setSourceBlock(str(common.get("sourceBlock")))
                .setPosition(common.get("position") instanceof Number n ? n.intValue() : null)
                .setProps(r.result.props().isEmpty() ? null : new LinkedHashMap<>(r.result.props()))
                .setBot(s.bot())
                .setBotReason(s.botReason())
                .setInternal(identity.internal || s.internal())
                .setNewVisitor(s.newVisitor())
                .setLoggedIn(identity.loggedIn);
        Attribution a = s.attribution();
        if (a != null) {
            e.setChannel(a.channel()).setSource(a.source()).setMedium(a.medium()).setCampaign(a.campaign());
        }
        enrich(e, entities);
        return e;
    }

    static void enrich(AnalyticsEvent e, EntityLookup entities) {
        if (e.getEntityType() == null || e.getEntityId() == null) {
            return;
        }
        if ("COMPANY".equals(e.getEntityType()) && e.getCompanyId() == null) {
            e.setCompanyId(e.getEntityId());
        }
        if (entities == null) {
            return;
        }
        EntityInfo info = entities.find(e.getEntityType(), e.getEntityId());
        if (info == null) {
            return;
        }
        if (info.companyId() != null) {
            e.setCompanyId(info.companyId());
        }
        if (info.categoryId() != null && e.getCategoryId() == null) {
            e.setCategoryId(info.categoryId());
        }
        if (info.test()) {
            e.setTest(true);
        }
    }

    private Identity identity(CollectRequest request, String consent, String clientVid, Context ctx) {
        Identity id = new Identity();
        UserAgentParser.UserAgentInfo ua = UserAgentParser.parse(request.userAgent());
        id.device = ua.device();
        id.os = ua.os();
        id.browser = ctx.app != null && UserAgentParser.OTHER.equals(ua.browser()) ? "App" : ua.browser();
        id.botReason = bots.userAgent(request.userAgent(), ctx.webdriver);
        String ip = request.ip();
        InetAddress address = IpAnonymizer.parse(ip);
        if (address != null) {
            try {
                AnalyticsGeoResolver.Geo g = geo.lookup(address);
                if (g != null) {
                    id.country = g.country();
                    id.region = g.region();
                    id.city = g.city();
                }
            } catch (RuntimeException e) {
                log.debug("[analytics] geo lookup failed: {}", e.getMessage());
            }
        }
        String truncated = IpAnonymizer.truncate(ip);
        if (properties.isStoreRawIp() && address != null) {
            id.rawIp = address.getHostAddress();
        }
        Authentication auth = request.authentication();
        String userId = null;
        try {
            userId = principals.userId(auth);
            id.internal = principals.isInternal(auth);
            id.loggedIn = principals.loggedIn(auth);
        } catch (RuntimeException e) {
            log.debug("[analytics] principal resolve failed: {}", e.getMessage());
        }
        String day = sessionizer.day(clock.millis());
        if (FULL.equals(consent) && clientVid != null && !CookielessHasher.isCookieless(clientVid)) {
            id.vid = clientVid;
            id.uid = userId;
        } else {
            id.vid = hasher.visitorId(day, request.userAgent(), truncated, request.host());
        }
        return id;
    }

    private Raw raw(Object o, long now, NaqqaAnalyticsProperties.Limits limits) {
        Raw r = new Raw();
        if (!(o instanceof Map<?, ?> m)) {
            r.reason = "bad_event";
            return r;
        }
        Object name = m.get("name");
        if (!(name instanceof String n) || n.length() > 60) {
            r.reason = "bad_event";
            return r;
        }
        Object t = m.get("t");
        long ts = t instanceof Number num ? num.longValue() : now;
        if (ts > now + limits.getMaxFutureSkewMs() || ts < now - limits.getMaxPastMs()) {
            r.reason = "bad_ts";
            return r;
        }
        r.ts = Math.min(ts, now);
        Map<String, Object> props = new LinkedHashMap<>();
        if (m.get("props") instanceof Map<?, ?> p) {
            p.forEach((k, v) -> props.put(String.valueOf(k), v));
        } else if (m.get("props") != null) {
            r.reason = "bad_event";
            return r;
        }
        RegistryValidator.Result result = validator.validate(n, props, false);
        if (!result.ok()) {
            r.reason = result.reason();
            return r;
        }
        if (result.droppedProps() > 0) {
            quality.reject("dropped_props", result.droppedProps());
        }
        r.result = result;
        String id = safeId(m.get("id"));
        r.id = id != null ? id : UUID.randomUUID().toString();
        r.path = m.get("path") instanceof String s ? PiiScrubber.sanitizePath(s, limits.getMaxPathLength()) : null;
        r.pageType = pageType(m.get("pageType"));
        return r;
    }

    private Context context(Object o) {
        Context c = new Context();
        if (!(o instanceof Map<?, ?> m)) {
            return c;
        }
        NaqqaAnalyticsProperties.Limits limits = properties.getLimits();
        if (m.get("lang") instanceof String s) {
            String l = s.trim().toLowerCase(Locale.ROOT);
            if (l.length() > 2) {
                l = l.substring(0, 2);
            }
            c.lang = LANG.matcher(l).matches() ? l : null;
        }
        c.path = m.get("path") instanceof String s ? PiiScrubber.sanitizePath(s, limits.getMaxPathLength()) : null;
        c.pageType = pageType(m.get("pageType"));
        c.ref = m.get("ref") instanceof String s ? PiiScrubber.sanitizeUrl(s, limits.getMaxUrlLength()) : null;
        if (m.get("utm") instanceof Map<?, ?> u) {
            c.utmSource = text(u.get("source"), 100);
            c.utmMedium = text(u.get("medium"), 100);
            c.utmCampaign = text(u.get("campaign"), 150);
            c.utmTerm = text(u.get("term"), 100);
            c.utmContent = text(u.get("content"), 100);
        }
        if (m.get("click") instanceof String s) {
            String v = s.trim().toLowerCase(Locale.ROOT);
            c.click = switch (v) {
                case "gclid", "fbclid", "yclid", "msclkid", "ttclid" -> v;
                default -> null;
            };
        }
        c.refParam = text(m.get("refParam"), 80);
        c.screen = m.get("screen") instanceof String s && SIZE.matcher(s).matches() ? s : null;
        c.viewport = m.get("viewport") instanceof String s && SIZE.matcher(s).matches() ? s : null;
        c.app = text(m.get("app"), 40);
        c.webdriver = m.get("webdriver") instanceof Boolean b ? b : null;
        return c;
    }

    private static String pageType(Object o) {
        if (!(o instanceof String s)) {
            return null;
        }
        String v = s.trim().toLowerCase(Locale.ROOT);
        return PAGE_TYPE.matcher(v).matches() ? v : null;
    }

    private static String text(Object o, int max) {
        if (!(o instanceof String s)) {
            return null;
        }
        String v = PiiScrubber.truncate(PiiScrubber.scrub(s.trim()), max);
        return v == null || v.isEmpty() ? null : v;
    }

    static String safeId(Object o) {
        return o instanceof String s && SAFE_ID.matcher(s).matches() ? s : null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static void putIf(Map<String, String> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private Object lock(String key) {
        return locks[Math.floorMod(key.hashCode(), LOCKS)];
    }

    public Duration inactivity() {
        return Duration.ofMinutes(properties.getSessionTimeoutMinutes());
    }

    static final class Raw {
        String reason;
        long ts;
        String id;
        String path;
        String pageType;
        RegistryValidator.Result result;
    }

    static final class Context {
        String lang;
        String path;
        String pageType;
        String ref;
        String utmSource;
        String utmMedium;
        String utmCampaign;
        String utmTerm;
        String utmContent;
        String click;
        String refParam;
        String screen;
        String viewport;
        String app;
        Boolean webdriver;
    }

    static final class Identity {
        String vid;
        String uid;
        String device;
        String os;
        String browser;
        String country;
        String region;
        String city;
        String botReason;
        String rawIp;
        boolean internal;
        boolean loggedIn;
    }

    public record CollectRequest(byte[] body, String ip, String userAgent, Authentication authentication, String host) {
    }

    public record CollectResult(int status, String code, int accepted, int rejected, String sid, long retryAfterSeconds) {

        static CollectResult status(int status, String code) {
            return new CollectResult(status, code, 0, 0, null, 0);
        }
    }
}
