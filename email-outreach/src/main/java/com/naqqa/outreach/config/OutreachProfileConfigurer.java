package com.naqqa.outreach.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.outreach.config.OutreachProperties.ProfileConfig;
import com.naqqa.outreach.entity.OutreachProfileEntity;
import com.naqqa.outreach.repository.OutreachProfileRepository;
import com.naqqa.outreach.service.SecretCipher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Provisions sender profiles from the host backend's configuration ({@code naqqa.outreach.profiles})
 * into the {@code outreach_profiles} collection on startup. Config is the source of truth for the
 * fields it supplies (only non-null values are applied, so a partially-specified profile keeps its
 * stored values); a profile missing a mailbox or app password is skipped so the sender never runs
 * with broken credentials. Lets sender accounts be configured from the backend/env instead of the
 * library or the admin API.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutreachProfileConfigurer {

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final OutreachProperties props;
    private final OutreachProfileRepository repo;
    private final SecretCipher cipher;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper mapper = new ObjectMapper();

    @EventListener(ApplicationReadyEvent.class)
    public void provision() {
        for (ProfileConfig cfg : props.getProfiles()) {
            if (isBlank(cfg.getKey())) {
                continue;
            }
            if (isBlank(cfg.getFromEmail()) || isBlank(cfg.getAppPassword())) {
                log.info("Outreach profile '{}' skipped — mailbox or app password not configured.", cfg.getKey());
                continue;
            }
            try {
                repo.save(apply(cfg, repo.findByKey(cfg.getKey()).orElseGet(() -> {
                    OutreachProfileEntity n = new OutreachProfileEntity();
                    n.setKey(cfg.getKey());
                    n.setCreatedAt(Instant.now());
                    return n;
                })));
                log.info("Provisioned outreach sender profile '{}' from config.", cfg.getKey());
            } catch (Exception e) {
                log.error("Failed to provision outreach profile '{}'", cfg.getKey(), e);
            }
        }
    }

    private OutreachProfileEntity apply(ProfileConfig cfg, OutreachProfileEntity p) {
        p.setFromEmail(cfg.getFromEmail());
        p.setAppPassword(cipher.encrypt(cfg.getAppPassword()));
        if (cfg.getFromName() != null) p.setFromName(cfg.getFromName());
        if (cfg.getSignature() != null) p.setSignature(cfg.getSignature());
        if (cfg.getSmtpHost() != null) p.setSmtpHost(cfg.getSmtpHost());
        if (cfg.getSmtpPort() != null) p.setSmtpPort(cfg.getSmtpPort());
        if (cfg.getImapHost() != null) p.setImapHost(cfg.getImapHost());
        if (cfg.getImapPort() != null) p.setImapPort(cfg.getImapPort());
        if (cfg.getDailyLimit() != null) p.setDailyLimit(cfg.getDailyLimit());
        applyWarmup(cfg, p);
        if (cfg.getEnabled() != null) p.setEnabled(cfg.getEnabled());
        p.setUpdatedAt(Instant.now());
        return p;
    }

    /** Warm-up ramp: a {@code warmupFile} (startDate + schedule) wins; otherwise the inline fields. */
    private void applyWarmup(ProfileConfig cfg, OutreachProfileEntity p) {
        if (cfg.getWarmupFile() != null && !cfg.getWarmupFile().isBlank()) {
            if (loadWarmupFile(cfg.getKey(), cfg.getWarmupFile().trim(), p)) {
                return;
            }
        }
        if (cfg.getWarmupSchedule() != null) {
            p.setWarmupSchedule(cfg.getWarmupSchedule());
        }
        if (cfg.getWarmupStartDate() != null && !cfg.getWarmupStartDate().isBlank()) {
            try {
                p.setWarmupStartDate(LocalDate.parse(cfg.getWarmupStartDate().trim()));
            } catch (Exception ignored) {
                // leave existing / null on bad input
            }
        }
    }

    /** Reads {@code {"startDate":"dd/MM/yyyy","schedule":[...]}} onto the profile. @return true on success. */
    private boolean loadWarmupFile(String key, String location, OutreachProfileEntity p) {
        try {
            Resource res = resourceLoader.getResource(location);
            if (!res.exists()) {
                log.warn("Warm-up file '{}' for profile '{}' not found — falling back to inline warm-up.",
                        location, key);
                return false;
            }
            JsonNode node;
            try (InputStream in = res.getInputStream()) {
                node = mapper.readTree(in);
            }
            List<Integer> schedule = new ArrayList<>();
            if (node.has("schedule")) {
                node.get("schedule").forEach(n -> schedule.add(n.asInt()));
            }
            String start = node.path("startDate").asText(null);
            if (schedule.isEmpty() || start == null) {
                log.warn("Warm-up file '{}' for profile '{}' missing startDate/schedule.", location, key);
                return false;
            }
            p.setWarmupSchedule(schedule);
            p.setWarmupStartDate(LocalDate.parse(start.trim(), DMY));
            log.info("Loaded warm-up for '{}' from {} (start {}, {} days, cap {}).",
                    key, location, start, schedule.size(), schedule.get(schedule.size() - 1));
            return true;
        } catch (Exception e) {
            log.error("Failed to load warm-up file '{}' for profile '{}'", location, key, e);
            return false;
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
