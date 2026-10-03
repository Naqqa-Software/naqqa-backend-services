package com.naqqa.chatbot.ai.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.chatbot.spi.ChatContentProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Slf4j
public class ChatRetrievalService {

    private static final long REDIS_BACKOFF_MS = 60_000L;

    private final ChatContentProvider provider;
    private final ObjectMapper objectMapper;
    private final Supplier<StringRedisTemplate> redis;
    private final String prefix;
    private final String versionKey;
    private final Duration ttl;
    private final Map<String, ChatItemType> types = new LinkedHashMap<>();
    private volatile long redisDownUntil;
    private java.util.function.Predicate<String> blockedTitle = t -> false;

    public void setBlockedTitle(java.util.function.Predicate<String> blockedTitle) {
        this.blockedTitle = blockedTitle == null ? t -> false : blockedTitle;
    }

    private List<Candidate> safe(List<Candidate> candidates) {
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : candidates) {
            boolean blocked = false;
            for (String title : c.titles().values()) {
                if (title != null && blockedTitle.test(title)) {
                    blocked = true;
                    break;
                }
            }
            if (!blocked) {
                out.add(c);
            }
        }
        return out;
    }

    public ChatRetrievalService(ChatContentProvider provider, ObjectMapper objectMapper, Supplier<StringRedisTemplate> redis,
                                String prefix, Duration ttl) {
        this.provider = provider;
        this.objectMapper = objectMapper;
        this.redis = redis == null ? () -> null : redis;
        this.prefix = prefix == null || prefix.isBlank() ? "chatbot:ret:" : prefix;
        this.versionKey = this.prefix + "version";
        this.ttl = ttl == null ? Duration.ofMinutes(5) : ttl;
        if (provider != null) {
            for (ChatItemType t : provider.itemTypes()) {
                types.put(t.key(), t);
            }
        }
    }

    public ChatContentProvider provider() {
        return provider;
    }

    public ChatItemType type(String key) {
        return key == null ? null : types.get(key);
    }

    public List<String> typeKeys() {
        return List.copyOf(types.keySet());
    }

    public boolean isAvailable(String type) {
        if (provider == null || !types.containsKey(type)) {
            return false;
        }
        try {
            return provider.isAvailable(type);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public List<String> usable(List<String> requested, boolean priced) {
        List<String> out = new ArrayList<>();
        if (requested == null) {
            return out;
        }
        for (String t : requested) {
            ChatItemType type = types.get(t);
            if (type == null || out.contains(t)) {
                continue;
            }
            if (priced && !type.priced()) {
                continue;
            }
            out.add(t);
        }
        return out;
    }

    public List<Candidate> retrieve(RetrievalPlan plan) {
        if (provider == null || plan == null || plan.types() == null || plan.types().isEmpty()) {
            return List.of();
        }
        String cacheKey = cacheKey(plan);
        List<Candidate> cached = readCache(cacheKey);
        if (cached != null) {
            return safe(cached);
        }
        List<Candidate> out;
        try {
            List<Candidate> found = provider.retrieve(plan);
            out = found == null ? List.of() : new ArrayList<>(found);
        } catch (RuntimeException e) {
            log.debug("[chatbot] retrieval failed: {}", e.getMessage());
            out = List.of();
        }
        out = safe(out);
        writeCache(cacheKey, out);
        return out;
    }

    public void bumpVersion() {
        StringRedisTemplate template = template();
        if (template == null) {
            return;
        }
        try {
            template.opsForValue().increment(versionKey);
        } catch (RuntimeException e) {
            markRedisDown(e);
        }
    }

    private String cacheKey(RetrievalPlan plan) {
        String token;
        try {
            token = provider.cacheToken(plan);
        } catch (RuntimeException e) {
            token = "";
        }
        String raw = plan.types() + "|" + plan.query() + "|" + plan.lang() + "|" + plan.companyId() + "|"
                + (plan.category() == null ? "" : plan.category().taxonomy() + ":" + plan.category().id()) + "|"
                + plan.browse() + "|" + plan.perType() + "|" + plan.priceMin() + "|" + plan.priceMax() + "|" + plan.sortDiscount()
                + "|" + (plan.place() == null ? "" : plan.place().kind() + ":" + plan.place().id()) + "|" + plan.intent()
                + "|" + plan.relax() + "|" + plan.minDiscount() + "|" + plan.sort()
                + "|" + token + "|" + LocalDate.now();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    private List<Candidate> readCache(String hash) {
        StringRedisTemplate template = template();
        if (template == null) {
            return null;
        }
        try {
            String version = template.opsForValue().get(versionKey);
            String json = template.opsForValue().get(prefix + (version == null ? "0" : version) + ":" + hash);
            if (json == null) {
                return null;
            }
            return objectMapper.readValue(json, new TypeReference<List<Candidate>>() {
            });
        } catch (Exception e) {
            markRedisDown(e);
            return null;
        }
    }

    private void writeCache(String hash, List<Candidate> candidates) {
        StringRedisTemplate template = template();
        if (template == null) {
            return;
        }
        try {
            String version = template.opsForValue().get(versionKey);
            template.opsForValue().set(prefix + (version == null ? "0" : version) + ":" + hash,
                    objectMapper.writeValueAsString(candidates), ttl);
        } catch (Exception e) {
            markRedisDown(e);
        }
    }

    private StringRedisTemplate template() {
        if (System.currentTimeMillis() < redisDownUntil) {
            return null;
        }
        try {
            return redis.get();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private void markRedisDown(Exception e) {
        redisDownUntil = System.currentTimeMillis() + REDIS_BACKOFF_MS;
        log.debug("[chatbot] redis cache unavailable: {}", e.getMessage());
    }
}
