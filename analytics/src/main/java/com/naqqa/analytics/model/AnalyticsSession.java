package com.naqqa.analytics.model;

import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.data.annotation.Id;

import java.time.Instant;
import java.util.Map;

@Data
@Accessors(chain = true)
public class AnalyticsSession {

    @Id
    private String sid;
    private String clientSid;
    private String vid;
    private String day;
    private Instant start;
    private Instant end;
    private int pages;
    private long activeMs;
    private boolean bounce;
    private String landing;
    private String referrer;
    private String exit;
    private String channel;
    private String source;
    private String medium;
    private String campaign;
    private String campaignKey;
    private Map<String, String> utm;
    private String device;
    private String os;
    private String browser;
    private String lang;
    private String country;
    private String region;
    private String city;
    private Boolean newVisitor;
    private boolean bot;
    private boolean internal;
    private boolean loggedIn;
}
