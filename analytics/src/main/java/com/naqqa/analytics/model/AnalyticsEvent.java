package com.naqqa.analytics.model;

import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.data.annotation.Id;

import java.time.Instant;
import java.util.Map;

@Data
@Accessors(chain = true)
public class AnalyticsEvent {

    @Id
    private String id;
    private Instant ts;
    private Instant rcv;
    private String day;
    private String name;
    private String vid;
    private String sid;
    private String uid;
    private String consent;
    private String lang;
    private String path;
    private String pageType;
    private String channel;
    private String source;
    private String medium;
    private String campaign;
    private String landing;
    private String referrer;
    private String ip;
    private String device;
    private String os;
    private String browser;
    private String country;
    private String region;
    private String city;
    private String entityType;
    private String entityId;
    private String companyId;
    private String categoryId;
    private Boolean sponsored;
    private String sourceBlock;
    private Integer position;
    private Map<String, Object> props;
    private boolean bot;
    private String botReason;
    private boolean internal;
    private boolean test;
    private Boolean newVisitor;
    private boolean loggedIn;

    public long epochMs() {
        return ts == null ? 0L : ts.toEpochMilli();
    }

    public Object prop(String key) {
        return props == null ? null : props.get(key);
    }

    public Long longProp(String key) {
        Object v = prop(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public Double doubleProp(String key) {
        Object v = prop(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        return null;
    }

    public String stringProp(String key) {
        Object v = prop(key);
        return v == null ? null : String.valueOf(v);
    }

    public boolean boolProp(String key) {
        Object v = prop(key);
        return v instanceof Boolean b ? b : "true".equals(v);
    }

    public String entityKey() {
        return entityType == null || entityId == null ? null : entityType + ":" + entityId;
    }
}
