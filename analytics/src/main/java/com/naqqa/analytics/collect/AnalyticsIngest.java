package com.naqqa.analytics.collect;

import com.naqqa.analytics.model.AnalyticsEvent;
import com.naqqa.analytics.registry.PiiScrubber;
import com.naqqa.analytics.registry.RegistryValidator;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public class AnalyticsIngest {

    private final RegistryValidator validator;
    private final SessionCache sessions;
    private final Sessionizer sessionizer;
    private final EntityLookup entities;
    private final BotDetector bots;
    private final Consumer<List<AnalyticsEvent>> sink;
    private final RealtimeCounters realtime;
    private final QualityCounters quality;
    private final Clock clock;

    public AnalyticsIngest(RegistryValidator validator, SessionCache sessions, Sessionizer sessionizer, EntityLookup entities,
                           BotDetector bots, Consumer<List<AnalyticsEvent>> sink, RealtimeCounters realtime, QualityCounters quality,
                           Clock clock) {
        this.validator = validator;
        this.sessions = sessions;
        this.sessionizer = sessionizer;
        this.entities = entities;
        this.bots = bots;
        this.sink = sink;
        this.realtime = realtime;
        this.quality = quality;
        this.clock = clock;
    }

    public boolean track(ServerEvent event) {
        AnalyticsEvent e = build(event);
        if (e == null) {
            return false;
        }
        List<AnalyticsEvent> list = List.of(e);
        sink.accept(list);
        if (realtime != null) {
            realtime.record(list);
        }
        return true;
    }

    public AnalyticsEvent build(ServerEvent event) {
        if (event == null || event.name() == null) {
            return null;
        }
        Map<String, Object> raw = new LinkedHashMap<>();
        if (event.props() != null) {
            raw.putAll(event.props());
        }
        putIf(raw, "entityType", event.entityType());
        putIf(raw, "entityId", event.entityId());
        putIf(raw, "companyId", event.companyId());
        putIf(raw, "categoryId", event.categoryId());
        putIf(raw, "sponsored", event.sponsored());
        putIf(raw, "sourceBlock", event.sourceBlock());
        putIf(raw, "position", event.position());
        RegistryValidator.Result result = validator.validate(event.name(), raw, true);
        if (!result.ok()) {
            quality.reject(result.reason(), 1);
            return null;
        }
        long now = clock.millis();
        long ts = event.ts() == null ? now : Math.min(event.ts(), now);
        String clientSid = CollectorService.safeId(event.sid());
        SessionState s = clientSid == null ? null : sessions.get(clientSid);
        Map<String, Object> common = result.common();
        AnalyticsEvent e = new AnalyticsEvent()
                .setId(UUID.randomUUID().toString())
                .setTs(Instant.ofEpochMilli(ts))
                .setRcv(Instant.ofEpochMilli(now))
                .setDay(sessionizer.day(ts))
                .setName(result.name())
                .setUid(event.uid())
                .setLang(event.lang())
                .setPath(PiiScrubber.sanitizePath(event.path(), 300))
                .setPageType(event.pageType())
                .setEntityType(str(common.get("entityType")))
                .setEntityId(str(common.get("entityId")))
                .setCompanyId(str(common.get("companyId")))
                .setCategoryId(str(common.get("categoryId")))
                .setSponsored(common.get("sponsored") instanceof Boolean b ? b : null)
                .setSourceBlock(str(common.get("sourceBlock")))
                .setPosition(common.get("position") instanceof Number n ? n.intValue() : null)
                .setProps(result.props().isEmpty() ? null : new LinkedHashMap<>(result.props()))
                .setInternal(Boolean.TRUE.equals(event.internal()));
        String vid = CollectorService.safeId(event.vid());
        if (s != null) {
            e.setSid(s.sid())
                    .setVid(vid != null ? vid : s.vid())
                    .setLanding(s.landing())
                    .setDevice(s.device())
                    .setOs(s.os())
                    .setBrowser(s.browser())
                    .setCountry(s.country())
                    .setRegion(s.region())
                    .setCity(s.city())
                    .setNewVisitor(s.newVisitor())
                    .setBot(s.bot())
                    .setBotReason(s.botReason())
                    .setInternal(e.isInternal() || s.internal())
                    .setLoggedIn(s.loggedIn() || event.uid() != null)
                    .setConsent(s.vid() != null && CookielessHasher.isCookieless(s.vid()) ? CollectorService.COOKIELESS : CollectorService.FULL);
            if (e.getLang() == null) {
                e.setLang(s.lang());
            }
            if (s.attribution() != null) {
                e.setChannel(s.attribution().channel()).setSource(s.attribution().source())
                        .setMedium(s.attribution().medium()).setCampaign(s.attribution().campaign());
            }
        } else {
            e.setSid(clientSid).setVid(vid).setLoggedIn(event.uid() != null)
                    .setConsent(vid == null ? CollectorService.COOKIELESS : CollectorService.FULL);
            if (event.userAgent() != null) {
                UserAgentParser.UserAgentInfo ua = UserAgentParser.parse(event.userAgent());
                e.setDevice(ua.device()).setOs(ua.os()).setBrowser(ua.browser());
                String reason = bots.userAgent(event.userAgent(), null);
                if (reason != null) {
                    e.setBot(true).setBotReason(reason);
                }
            }
        }
        CollectorService.enrich(e, entities);
        return e;
    }

    private static void putIf(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    public record ServerEvent(String name, Long ts, String vid, String sid, String uid, String lang, String path, String pageType,
                              String entityType, String entityId, String companyId, String categoryId, Boolean sponsored,
                              String sourceBlock, Integer position, Map<String, Object> props, String userAgent, String ip,
                              Boolean internal) {

        public static ServerEvent of(String name, String vid, String sid, Map<String, Object> props) {
            return new ServerEvent(name, null, vid, sid, null, null, null, null, null, null, null, null, null, null, null, props,
                    null, null, null);
        }
    }
}
