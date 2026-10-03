package com.naqqa.chatbot.service;

import com.naqqa.chatbot.config.NaqqaChatbotProperties;
import com.naqqa.chatbot.i18n.ChatLanguages;
import com.naqqa.chatbot.dto.ChatDtos.ChatSettingsDto;
import com.naqqa.chatbot.entities.ChatSettingsEntity;
import com.naqqa.chatbot.entities.ChatSettingsEntity.CannedReply;
import com.naqqa.chatbot.entities.ChatSettingsEntity.QuickReply;
import com.naqqa.chatbot.entities.ChatSettingsEntity.ScheduleSlot;
import com.naqqa.chatbot.repository.ChatSettingsRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
public class ChatSettingsService {

    private static final long CACHE_MILLIS = 15_000L;
    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z0-9_]{1,40}$");

    private final ChatSettingsRepository repository;
    private final NaqqaChatbotProperties properties;
    private final ChatLanguages languages;
    private final List<String> welcomeQuickReplies;

    private List<com.naqqa.chatbot.spi.ChatSettingsMigration> migrations = List.of();
    private volatile ChatSettingsEntity cached;
    private volatile long cachedAt;

    public ChatSettingsService(ChatSettingsRepository repository, NaqqaChatbotProperties properties, ChatLanguages languages,
                               List<String> welcomeQuickReplies) {
        this.repository = repository;
        this.properties = properties;
        this.languages = languages;
        this.welcomeQuickReplies = welcomeQuickReplies == null ? List.of() : List.copyOf(welcomeQuickReplies);
    }

    public void onReady() {
        try {
            get();
        } catch (Exception e) {
            log.warn("Chat settings initialisation failed: {}", e.getMessage());
        }
    }

    public void setMigrations(List<com.naqqa.chatbot.spi.ChatSettingsMigration> migrations) {
        this.migrations = migrations == null ? List.of() : List.copyOf(migrations);
    }

    public ChatSettingsEntity get() {
        ChatSettingsEntity current = cached;
        if (current != null && System.currentTimeMillis() - cachedAt < CACHE_MILLIS) {
            return current;
        }
        ChatSettingsEntity loaded = repository.findById(ChatSettingsEntity.DEFAULT_ID).orElseGet(() -> repository.save(defaults()));
        boolean changed = false;
        for (com.naqqa.chatbot.spi.ChatSettingsMigration migration : migrations) {
            try {
                changed |= migration.migrate(loaded);
            } catch (RuntimeException e) {
                log.warn("Chat settings migration failed: {}", e.getMessage());
            }
        }
        if (changed) {
            loaded = repository.save(loaded);
        }
        cached = loaded;
        cachedAt = System.currentTimeMillis();
        return loaded;
    }

    public boolean isEnabled() {
        return properties.isEnabled() && get().isEnabled();
    }

    public ChatSettingsEntity update(ChatSettingsDto dto, Long operatorId) {
        ChatSettingsEntity current = get();
        ChatSettingsEntity next = validate(dto, current, languages.languages());
        next.setId(ChatSettingsEntity.DEFAULT_ID);
        next.setUpdatedAt(Instant.now());
        next.setUpdatedBy(operatorId);
        ChatSettingsEntity saved = repository.save(next);
        cached = saved;
        cachedAt = System.currentTimeMillis();
        return saved;
    }

    public ChatSettingsEntity updateAvatar(String avatarUrl, Long operatorId) {
        ChatSettingsEntity current = get();
        current.setAvatarUrl(avatarUrl);
        current.setUpdatedAt(Instant.now());
        current.setUpdatedBy(operatorId);
        ChatSettingsEntity saved = repository.save(current);
        cached = saved;
        cachedAt = System.currentTimeMillis();
        return saved;
    }

    public static ChatSettingsDto toDto(ChatSettingsEntity s) {
        return new ChatSettingsDto(s.isEnabled(), s.getBotName(), s.getAvatarUrl(), s.getWelcome(), s.getQuickReplies(),
                s.isVoiceEnabled(), s.getOperatorSchedule(), s.getTimezone(), s.getMinConfidence(),
                s.getMaxSponsoredPerReply(), s.getMaxCards(), s.getSponsorBoost(), s.getExcludeTitlePatterns(),
                s.getCannedReplies(), s.getRetentionDays(), s.isLlmEnabled(),
                s.isAutoReadReplies(), s.getUpdatedAt(), s.getUpdatedBy(), s.getLlmConfidenceThreshold());
    }

    public static ChatSettingsEntity validate(ChatSettingsDto dto, ChatSettingsEntity current, List<String> langs) {
        if (dto == null) {
            throw ChatException.badRequest("Settings body is required.");
        }
        ChatSettingsEntity s = new ChatSettingsEntity();
        s.setEnabled(dto.enabled());
        String botName = trim(dto.botName());
        if (botName == null || botName.isEmpty() || botName.length() > 60) {
            throw ChatException.badRequest("botName must be 1..60 characters.");
        }
        s.setBotName(botName);
        s.setAvatarUrl(current != null ? current.getAvatarUrl() : null);
        if (dto.avatarUrl() == null || dto.avatarUrl().isBlank()) {
            s.setAvatarUrl(null);
        } else if (current != null && dto.avatarUrl().equals(current.getAvatarUrl())) {
            s.setAvatarUrl(current.getAvatarUrl());
        } else if (dto.avatarUrl().startsWith("https://") || dto.avatarUrl().startsWith("http://") || dto.avatarUrl().startsWith("/")) {
            if (dto.avatarUrl().length() > 500 || dto.avatarUrl().startsWith("//")) {
                throw ChatException.badRequest("avatarUrl is invalid.");
            }
            s.setAvatarUrl(dto.avatarUrl().trim());
        } else {
            throw ChatException.badRequest("avatarUrl is invalid.");
        }
        s.setWelcome(localized(dto.welcome(), 1000, "welcome", true, langs));
        List<QuickReply> quickReplies = new ArrayList<>();
        if (dto.quickReplies() != null) {
            if (dto.quickReplies().size() > 8) {
                throw ChatException.badRequest("At most 8 quick replies are allowed.");
            }
            Set<String> keys = new HashSet<>();
            for (QuickReply qr : dto.quickReplies()) {
                if (qr == null || qr.getKey() == null || !KEY_PATTERN.matcher(qr.getKey()).matches() || !keys.add(qr.getKey())) {
                    throw ChatException.badRequest("Quick reply keys must be unique and match [a-z0-9_]{1,40}.");
                }
                quickReplies.add(new QuickReply(qr.getKey(), localized(qr.getLabel(), 60, "quickReplies.label", true, langs)));
            }
        }
        s.setQuickReplies(quickReplies);
        s.setVoiceEnabled(dto.voiceEnabled());
        List<ScheduleSlot> schedule = new ArrayList<>();
        if (dto.operatorSchedule() != null) {
            if (dto.operatorSchedule().size() > 21) {
                throw ChatException.badRequest("Too many schedule slots.");
            }
            for (ScheduleSlot slot : dto.operatorSchedule()) {
                if (slot == null || slot.getDayOfWeek() < 1 || slot.getDayOfWeek() > 7) {
                    throw ChatException.badRequest("Schedule dayOfWeek must be 1..7.");
                }
                LocalTime from = ChatSchedule.parse(slot.getFrom());
                LocalTime to = ChatSchedule.parse(slot.getTo());
                if (from == null || to == null || !from.isBefore(to)) {
                    throw ChatException.badRequest("Schedule times must be HH:mm with from < to.");
                }
                schedule.add(new ScheduleSlot(slot.getDayOfWeek(), from.toString(), to.toString()));
            }
        }
        s.setOperatorSchedule(schedule);
        String timezone = trim(dto.timezone());
        try {
            ZoneId.of(timezone == null ? "" : timezone);
        } catch (Exception e) {
            throw ChatException.badRequest("timezone is invalid.");
        }
        s.setTimezone(timezone);
        s.setMinConfidence(range(dto.minConfidence(), 0, 1, "minConfidence"));
        s.setMaxSponsoredPerReply((int) range(dto.maxSponsoredPerReply(), 0, 5, "maxSponsoredPerReply"));
        s.setMaxCards((int) range(dto.maxCards(), 1, 10, "maxCards"));
        s.setSponsorBoost(range(dto.sponsorBoost(), 0, 10, "sponsorBoost"));
        List<String> patterns = new ArrayList<>();
        if (dto.excludeTitlePatterns() != null) {
            if (dto.excludeTitlePatterns().size() > 50) {
                throw ChatException.badRequest("At most 50 exclude patterns are allowed.");
            }
            for (String p : dto.excludeTitlePatterns()) {
                if (p == null || p.isBlank()) {
                    continue;
                }
                if (p.length() > 200) {
                    throw ChatException.badRequest("Exclude pattern is too long.");
                }
                try {
                    Pattern.compile(p);
                } catch (Exception e) {
                    throw ChatException.badRequest("Invalid exclude pattern: " + p);
                }
                patterns.add(p);
            }
        }
        s.setExcludeTitlePatterns(patterns);
        List<CannedReply> canned = new ArrayList<>();
        if (dto.cannedReplies() != null) {
            if (dto.cannedReplies().size() > 100) {
                throw ChatException.badRequest("At most 100 canned replies are allowed.");
            }
            for (CannedReply c : dto.cannedReplies()) {
                if (c == null) {
                    continue;
                }
                String title = trim(c.getTitle());
                if (title == null || title.isEmpty() || title.length() > 120) {
                    throw ChatException.badRequest("Canned reply title must be 1..120 characters.");
                }
                String id = c.getId() == null || c.getId().isBlank() ? UUID.randomUUID().toString() : c.getId().trim();
                canned.add(new CannedReply(id, title, localized(c.getText(), 2000, "cannedReplies.text", true, langs)));
            }
        }
        s.setCannedReplies(canned);
        s.setRetentionDays((int) range(dto.retentionDays(), 1, 3650, "retentionDays"));
        s.setLlmEnabled(dto.llmEnabled());
        s.setAutoReadReplies(dto.autoReadReplies());
        Double threshold = dto.llmConfidenceThreshold() != null ? dto.llmConfidenceThreshold()
                : current != null ? current.getLlmConfidenceThreshold() : null;
        if (threshold != null) {
            s.setLlmConfidenceThreshold(range(threshold, 0, 1, "llmConfidenceThreshold"));
        }
        return s;
    }

    public ChatSettingsEntity defaults() {
        ChatSettingsEntity s = new ChatSettingsEntity();
        s.setId(ChatSettingsEntity.DEFAULT_ID);
        s.setBotName(properties.getBotName() == null || properties.getBotName().isBlank() ? "Chat" : properties.getBotName().trim());
        s.setWelcome(new LinkedHashMap<>(languages.localizedTemplate("settings.welcome")));
        List<QuickReply> quickReplies = new ArrayList<>();
        for (String key : welcomeQuickReplies) {
            Map<String, String> label = languages.localizedTemplate("quick_reply." + key);
            quickReplies.add(new QuickReply(key, label.isEmpty() ? null : new LinkedHashMap<>(label)));
        }
        s.setQuickReplies(quickReplies);
        s.setVoiceEnabled(true);
        List<ScheduleSlot> schedule = new ArrayList<>();
        for (int day = 1; day <= 5; day++) {
            schedule.add(new ScheduleSlot(day, "09:00", "18:00"));
        }
        s.setOperatorSchedule(schedule);
        s.setTimezone(properties.getTimezone() == null || properties.getTimezone().isBlank() ? "Europe/Chisinau" : properties.getTimezone());
        s.setMinConfidence(0.45);
        s.setMaxSponsoredPerReply(2);
        s.setMaxCards(5);
        s.setSponsorBoost(1.5);
        s.setExcludeTitlePatterns(new ArrayList<>(List.of("(?i)\\btest\\b", "(?i)\\bqa\\b", "(?i)\\bdemo\\b")));
        s.setCannedReplies(new ArrayList<>());
        s.setRetentionDays(properties.getRetentionDays() > 0 ? properties.getRetentionDays() : 90);
        s.setLlmEnabled(true);
        s.setAutoReadReplies(false);
        s.setEnabled(true);
        s.setUpdatedAt(Instant.now());
        return s;
    }

    private static Map<String, String> localized(Map<String, String> text, int max, String field, boolean required, List<String> langs) {
        Map<String, String> out = new LinkedHashMap<>();
        boolean any = false;
        for (String lang : langs) {
            String v = text == null ? null : trim(text.get(lang));
            if (v != null && v.length() > max) {
                throw ChatException.badRequest(field + " must be at most " + max + " characters.");
            }
            if (v != null && !v.isEmpty()) {
                any = true;
            }
            out.put(lang, v);
        }
        if (required && !any) {
            throw ChatException.badRequest(field + " requires at least one language.");
        }
        return out;
    }

    private static double range(double value, double min, double max, String field) {
        if (Double.isNaN(value) || value < min || value > max) {
            throw ChatException.badRequest(field + " must be between " + min + " and " + max + ".");
        }
        return value;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
