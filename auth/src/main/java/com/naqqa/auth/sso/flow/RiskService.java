package com.naqqa.auth.sso.flow;

import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.sso.risk.RiskContext;
import com.naqqa.sso.risk.RiskDecision;
import com.naqqa.sso.risk.RiskEngine;
import com.naqqa.sso.risk.RiskProfile;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;
import org.bson.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class RiskService {

    public static final String COLLECTION = "sso_risk_profiles";
    static final int MAX_ENTRIES = 50;
    public static final String SIGNAL_HEADER = "X-Test-Risk-Signals";

    private final MongoTemplate template;
    private final RiskEngine engine;
    private final SsoProperties properties;
    private final Clock clock;

    public RiskService(MongoTemplate template, RiskEngine engine, SsoProperties properties, Clock clock) {
        this.template = template;
        this.engine = engine;
        this.properties = properties;
        this.clock = clock;
        try {
            template.updateMulti(new Query(Criteria.where("purgeAt").exists(false)),
                    new Update().set("purgeAt", Date.from(clock.instant().plus(properties.getRisk().getProfileRetention()))), COLLECTION);
        } catch (RuntimeException ignored) {
        }
    }

    public static String deviceKey(String deviceToken) {
        return deviceToken == null ? null : Bytes.b64url(Hashes.sha256(Bytes.utf8("device|" + deviceToken)));
    }

    public boolean isKnownDevice(String userId, String deviceToken) {
        if (deviceToken == null) {
            return false;
        }
        return template.exists(new Query(Criteria.where("_id").is(userId).and("devices").is(deviceKey(deviceToken))), COLLECTION);
    }

    public RiskDecision evaluate(String userId, String deviceToken, String ip, int recentFailures) {
        if (!properties.getRisk().isEnabled()) {
            return new RiskDecision(RiskDecision.Action.ALLOW, 0, List.of());
        }
        return withInjectedSignals(evaluateProfile(userId, deviceToken, ip, recentFailures));
    }

    private RiskDecision withInjectedSignals(RiskDecision base) {
        SsoProperties.Risk risk = properties.getRisk();
        if (!risk.isSignalOverride() || !properties.getSocial().isTestMode()
                || !(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return base;
        }
        String header = attributes.getRequest().getHeader(SIGNAL_HEADER);
        if (header == null || header.isBlank() || header.length() > 200) {
            return base;
        }
        int score = base.score();
        List<String> reasons = new ArrayList<>(base.reasons());
        for (String signal : header.split(",")) {
            String name = signal.trim().toLowerCase(Locale.ROOT);
            int add = switch (name) {
                case "new_device" -> risk.getNewDeviceScore();
                case "new_network" -> risk.getNewIpRangeScore();
                case "impossible_travel" -> risk.getImpossibleTravelScore();
                case "failure_velocity" -> risk.getFailureVelocityScore();
                default -> 0;
            };
            if (add > 0 && !reasons.contains(name)) {
                score += add;
                reasons.add(name);
            }
        }
        RiskDecision.Action action = score >= risk.getDenyThreshold() ? RiskDecision.Action.DENY
                : score >= risk.getStepUpThreshold() ? RiskDecision.Action.STEP_UP : base.action();
        return new RiskDecision(action, score, reasons);
    }

    private RiskDecision evaluateProfile(String userId, String deviceToken, String ip, int recentFailures) {
        Document d = template.findById(userId, Document.class, COLLECTION);
        if (d == null) {
            return new RiskDecision(RiskDecision.Action.ALLOW, 0, List.of("first_login"));
        }
        Set<String> devices = new HashSet<>(d.getList("devices", String.class, List.of()));
        Set<String> networks = new HashSet<>(d.getList("networks", String.class, List.of()));
        Date last = d.getDate("lastLogin");
        RiskProfile profile = new RiskProfile(devices, networks, Set.of(), null, last == null ? null : last.toInstant(), Set.of(), ZoneId.of("UTC"));
        return engine.evaluate(new RiskContext(deviceKey(deviceToken), ip, null, null, false, recentFailures), profile);
    }

    public void remember(String userId, String deviceToken, String ip) {
        Update update = new Update().set("lastLogin", Date.from(clock.instant()))
                .set("purgeAt", Date.from(clock.instant().plus(properties.getRisk().getProfileRetention())));
        String device = deviceKey(deviceToken);
        if (device != null) {
            update.addToSet("devices", device);
        }
        update.addToSet("networks", RiskEngine.networkOf(ip));
        Query query = new Query(Criteria.where("_id").is(userId));
        template.upsert(query, update, COLLECTION);
        template.updateFirst(query, new Update().push("devices").slice(-MAX_ENTRIES).each().push("networks").slice(-MAX_ENTRIES).each(), COLLECTION);
    }
}
