package com.naqqa.analytics.banners.service;

import com.naqqa.analytics.banners.spi.BannerEventRecorder;
import com.naqqa.analytics.collect.AnalyticsIngest;
import com.naqqa.analytics.collect.EventWriter;
import com.naqqa.analytics.collect.UserAgentParser;
import com.naqqa.analytics.model.AnalyticsEvent;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class DefaultBannerEventRecorder implements BannerEventRecorder {

    private final ObjectProvider<AnalyticsIngest> ingest;
    private final ObjectProvider<EventWriter> writer;
    private final MongoTemplate mongo;
    private final String collection;
    private final ZoneId zone;

    public DefaultBannerEventRecorder(ObjectProvider<AnalyticsIngest> ingest, ObjectProvider<EventWriter> writer, MongoTemplate mongo,
                                      String collection, ZoneId zone) {
        this.ingest = ingest;
        this.writer = writer;
        this.mongo = mongo;
        this.collection = collection;
        this.zone = zone;
    }

    @Override
    public void record(BannerEvent e) {
        AnalyticsIngest in = ingest.getIfAvailable();
        if (in != null && in.track(toServerEvent(e))) {
            return;
        }
        AnalyticsEvent event = toEvent(e, zone);
        EventWriter w = writer.getIfAvailable();
        if (w != null) {
            w.offer(List.of(event));
        } else {
            mongo.insert(event, collection);
        }
    }

    public static AnalyticsIngest.ServerEvent toServerEvent(BannerEvent e) {
        return new AnalyticsIngest.ServerEvent(e.name(), e.ts() == null ? null : e.ts().toEpochMilli(), e.vid(), e.sid(), null, e.lang(),
                null, e.pageType(), "BANNER", e.campaignId(), e.companyId(), null, null, "banner", null, props(e), e.userAgent(), e.ip(), null);
    }

    static Map<String, Object> props(BannerEvent e) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("bannerId", e.bannerId());
        props.put("campaignId", e.campaignId());
        props.put("creativeId", e.creativeId());
        props.put("slot", e.slot());
        if (e.noPriorImpression()) {
            props.put("noPriorImpression", true);
        }
        props.values().removeIf(v -> v == null);
        return props;
    }

    public static AnalyticsEvent toEvent(BannerEvent e, ZoneId zone) {
        Instant ts = e.ts() == null ? Instant.now() : e.ts();
        AnalyticsEvent event = new AnalyticsEvent()
                .setId(UUID.randomUUID().toString())
                .setTs(ts)
                .setRcv(Instant.now())
                .setDay(ts.atZone(zone).toLocalDate().toString())
                .setName(e.name())
                .setVid(e.vid())
                .setSid(e.sid())
                .setLang(e.lang())
                .setPageType(e.pageType())
                .setEntityType("BANNER")
                .setEntityId(e.campaignId())
                .setCompanyId(e.companyId())
                .setSourceBlock("banner")
                .setProps(props(e));
        if (e.userAgent() != null) {
            UserAgentParser.UserAgentInfo ua = UserAgentParser.parse(e.userAgent());
            if (ua != null) {
                event.setDevice(ua.device()).setOs(ua.os()).setBrowser(ua.browser());
                event.setBot(UserAgentParser.BOT.equals(ua.device()));
            }
        }
        return event;
    }
}
