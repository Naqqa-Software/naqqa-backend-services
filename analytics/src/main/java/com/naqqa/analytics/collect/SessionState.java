package com.naqqa.analytics.collect;

import com.naqqa.analytics.collect.ChannelClassifier.Attribution;
import com.naqqa.analytics.model.AnalyticsSession;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Accessors(chain = true, fluent = true)
public class SessionState {

    private String sid;
    private String clientSid;
    private String vid;
    private long startMs;
    private long lastMs;
    private String day;
    private String campaignKey;
    private Attribution attribution;
    private String landing;
    private String referrer;
    private String exit;
    private int pageViews;
    private int interactions;
    private long activeMs;
    private boolean bot;
    private String botReason;
    private boolean internal;
    private boolean loggedIn;
    private Boolean newVisitor;
    private String device;
    private String os;
    private String browser;
    private String lang;
    private String country;
    private String region;
    private String city;
    private Map<String, String> utm;
    private boolean dirty;

    public AnalyticsSession toDocument() {
        AnalyticsSession s = new AnalyticsSession();
        s.setSid(sid);
        s.setClientSid(clientSid);
        s.setVid(vid);
        s.setDay(day);
        s.setStart(Instant.ofEpochMilli(startMs));
        s.setEnd(Instant.ofEpochMilli(lastMs));
        s.setPages(pageViews);
        s.setActiveMs(activeMs);
        s.setBounce(pageViews <= 1 && interactions == 0);
        s.setLanding(landing);
        s.setReferrer(referrer);
        s.setExit(exit);
        if (attribution != null) {
            s.setChannel(attribution.channel());
            s.setSource(attribution.source());
            s.setMedium(attribution.medium());
            s.setCampaign(attribution.campaign());
        }
        s.setCampaignKey(campaignKey);
        s.setUtm(utm == null ? null : new LinkedHashMap<>(utm));
        s.setDevice(device);
        s.setOs(os);
        s.setBrowser(browser);
        s.setLang(lang);
        s.setCountry(country);
        s.setRegion(region);
        s.setCity(city);
        s.setNewVisitor(newVisitor);
        s.setBot(bot);
        s.setInternal(internal);
        s.setLoggedIn(loggedIn);
        return s;
    }

    public static SessionState fromDocument(AnalyticsSession s) {
        if (s == null) {
            return null;
        }
        SessionState st = new SessionState()
                .sid(s.getSid())
                .clientSid(s.getClientSid())
                .vid(s.getVid())
                .day(s.getDay())
                .startMs(s.getStart() == null ? 0 : s.getStart().toEpochMilli())
                .lastMs(s.getEnd() == null ? 0 : s.getEnd().toEpochMilli())
                .pageViews(s.getPages())
                .activeMs(s.getActiveMs())
                .interactions(s.isBounce() ? 0 : 1)
                .landing(s.getLanding())
                .referrer(s.getReferrer())
                .exit(s.getExit())
                .campaignKey(s.getCampaignKey())
                .utm(s.getUtm())
                .device(s.getDevice())
                .os(s.getOs())
                .browser(s.getBrowser())
                .lang(s.getLang())
                .country(s.getCountry())
                .region(s.getRegion())
                .city(s.getCity())
                .newVisitor(s.getNewVisitor())
                .bot(s.isBot())
                .internal(s.isInternal())
                .loggedIn(s.isLoggedIn());
        if (s.getChannel() != null) {
            st.attribution(new Attribution(s.getChannel(), s.getSource(), s.getMedium(), s.getCampaign()));
        }
        return st;
    }
}
