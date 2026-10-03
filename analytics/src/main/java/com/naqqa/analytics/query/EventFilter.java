package com.naqqa.analytics.query;

import com.naqqa.analytics.model.AnalyticsEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

public final class EventFilter {

    public static final Set<String> BOOLEAN_FIELDS = Set.of("sponsored", "newVisitor", "loggedIn");

    public static final Map<String, String> FIELDS;
    private static final Map<String, Function<AnalyticsEvent, Object>> GETTERS;

    static {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("companyId", "companyId");
        f.put("categoryId", "categoryId");
        f.put("entityType", "entityType");
        f.put("entityId", "entityId");
        f.put("sponsored", "sponsored");
        f.put("lang", "lang");
        f.put("device", "device");
        f.put("os", "os");
        f.put("browser", "browser");
        f.put("city", "city");
        f.put("region", "region");
        f.put("country", "country");
        f.put("channel", "channel");
        f.put("source", "source");
        f.put("campaign", "campaign");
        f.put("landing", "landing");
        f.put("newVisitor", "newVisitor");
        f.put("loggedIn", "loggedIn");
        f.put("pageType", "pageType");
        f.put("path", "path");
        f.put("referrer", "referrer");
        f.put("sourceBlock", "sourceBlock");
        f.put("slot", "props.slot");
        f.put("campaignId", "props.campaignId");
        f.put("creativeId", "props.creativeId");
        f.put("intent", "props.intent");
        f.put("operatorId", "props.operatorId");
        FIELDS = Map.copyOf(f);
        Map<String, Function<AnalyticsEvent, Object>> g = new LinkedHashMap<>();
        g.put("companyId", AnalyticsEvent::getCompanyId);
        g.put("categoryId", AnalyticsEvent::getCategoryId);
        g.put("entityType", AnalyticsEvent::getEntityType);
        g.put("entityId", AnalyticsEvent::getEntityId);
        g.put("sponsored", AnalyticsEvent::getSponsored);
        g.put("lang", AnalyticsEvent::getLang);
        g.put("device", AnalyticsEvent::getDevice);
        g.put("os", AnalyticsEvent::getOs);
        g.put("browser", AnalyticsEvent::getBrowser);
        g.put("city", AnalyticsEvent::getCity);
        g.put("region", AnalyticsEvent::getRegion);
        g.put("country", AnalyticsEvent::getCountry);
        g.put("channel", AnalyticsEvent::getChannel);
        g.put("source", AnalyticsEvent::getSource);
        g.put("campaign", AnalyticsEvent::getCampaign);
        g.put("landing", AnalyticsEvent::getLanding);
        g.put("newVisitor", AnalyticsEvent::getNewVisitor);
        g.put("loggedIn", AnalyticsEvent::isLoggedIn);
        g.put("pageType", AnalyticsEvent::getPageType);
        g.put("path", AnalyticsEvent::getPath);
        g.put("referrer", AnalyticsEvent::getReferrer);
        g.put("sourceBlock", AnalyticsEvent::getSourceBlock);
        g.put("slot", e -> e.prop("slot"));
        g.put("campaignId", e -> e.prop("campaignId"));
        g.put("creativeId", e -> e.prop("creativeId"));
        g.put("intent", e -> e.prop("intent"));
        g.put("operatorId", e -> e.prop("operatorId"));
        GETTERS = Map.copyOf(g);
    }

    private EventFilter() {
    }

    public static Object typed(String key, String value) {
        if (BOOLEAN_FIELDS.contains(key)) {
            return Boolean.parseBoolean(value);
        }
        return value;
    }

    public static boolean isPrefix(String value) {
        return value != null && value.length() > 1 && value.endsWith("*");
    }

    public static boolean matches(AnalyticsQuery q, AnalyticsEvent e) {
        if (e == null || e.getTs() == null) {
            return false;
        }
        if (e.getTs().isBefore(q.start()) || !e.getTs().isBefore(q.endExclusive())) {
            return false;
        }
        if (!q.includeBots() && e.isBot()) {
            return false;
        }
        if (!q.includeInternal() && (e.isInternal() || e.isTest())) {
            return false;
        }
        if (q.companyIds() != null && (e.getCompanyId() == null || !q.companyIds().contains(e.getCompanyId()))) {
            return false;
        }
        for (Map.Entry<String, String> f : q.filters().entrySet()) {
            Function<AnalyticsEvent, Object> getter = GETTERS.get(f.getKey());
            if (getter == null) {
                continue;
            }
            Object actual = getter.apply(e);
            if (actual == null) {
                return false;
            }
            String value = f.getValue();
            if (isPrefix(value)) {
                if (!String.valueOf(actual).startsWith(value.substring(0, value.length() - 1))) {
                    return false;
                }
                continue;
            }
            Object expected = typed(f.getKey(), value);
            if (!Objects.equals(String.valueOf(actual), String.valueOf(expected))) {
                return false;
            }
        }
        return true;
    }
}
