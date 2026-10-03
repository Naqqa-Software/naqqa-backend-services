package com.naqqa.chatbot.ai.knowledge;

import com.naqqa.chatbot.ai.InputGuard;
import com.naqqa.chatbot.ai.TextNormalizer;
import com.naqqa.chatbot.ai.retrieval.ChatRetrievalService;
import com.naqqa.chatbot.entities.ChatKnowledgeChunkEntity;
import com.naqqa.chatbot.i18n.ChatLanguages;
import com.naqqa.chatbot.spi.ChatKnowledgeSearcher;
import com.naqqa.chatbot.spi.ChatKnowledgeSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class KnowledgeService {

    private static final long MEMORY_TTL_MS = 10 * 60 * 1000L;

    private final MongoTemplate mongoTemplate;
    private final String collection;
    private final List<ChatKnowledgeSource> sources;
    private final ChatKnowledgeSearcher searcher;
    private final ChatRetrievalService retrieval;
    private final ChatLanguages languages;
    private final InputGuard guard;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private List<java.util.Map.Entry<java.util.regex.Pattern, String>> replacements = List.of();
    private volatile List<ChatKnowledgeChunkEntity> memory;
    private volatile long memoryLoadedAt;

    public KnowledgeService(MongoTemplate mongoTemplate, String collection, List<ChatKnowledgeSource> sources,
                            ChatKnowledgeSearcher searcher, ChatRetrievalService retrieval, ChatLanguages languages,
                            InputGuard guard) {
        this.mongoTemplate = mongoTemplate;
        this.collection = collection == null || collection.isBlank() ? "chat_knowledge_chunk" : collection;
        this.sources = sources == null ? List.of() : List.copyOf(sources);
        this.searcher = searcher;
        this.retrieval = retrieval;
        this.languages = languages;
        this.guard = guard;
    }

    public void setReplacements(java.util.Map<String, String> values) {
        List<java.util.Map.Entry<java.util.regex.Pattern, String>> out = new ArrayList<>();
        if (values != null) {
            for (java.util.Map.Entry<String, String> e : values.entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null) {
                    continue;
                }
                java.util.regex.Pattern p = java.util.regex.Pattern.compile("(?iU)(?<![\\p{L}\\d])" + java.util.regex.Pattern.quote(e.getKey())
                        + "(?![\\p{L}\\d])");
                out.add(java.util.Map.entry(p, java.util.regex.Matcher.quoteReplacement(e.getValue())));
            }
        }
        this.replacements = List.copyOf(out);
    }

    public String replace(String value) {
        if (value == null || replacements.isEmpty()) {
            return value;
        }
        String out = value;
        for (java.util.Map.Entry<java.util.regex.Pattern, String> e : replacements) {
            out = e.getKey().matcher(out).replaceAll(e.getValue());
        }
        return out;
    }

    public String collection() {
        return collection;
    }

    public void initialIndex() {
        Thread t = new Thread(() -> {
            try {
                int n = reindex();
                log.info("[chatbot] knowledge base indexed with {} chunks", n);
            } catch (RuntimeException e) {
                log.warn("[chatbot] initial knowledge indexing failed: {}", e.getMessage());
            }
        }, "chatbot-knowledge-init");
        t.setDaemon(true);
        t.start();
    }

    public void nightly() {
        try {
            reindex();
        } catch (RuntimeException e) {
            log.warn("[chatbot] nightly knowledge reindex failed: {}", e.getMessage());
        }
    }

    public int reindex() {
        if (!running.compareAndSet(false, true)) {
            return (int) mongoTemplate.getCollection(collection).estimatedDocumentCount();
        }
        try {
            List<ChatKnowledgeChunkEntity> chunks = chunks();
            Set<Long> ids = new HashSet<>();
            for (ChatKnowledgeChunkEntity chunk : chunks) {
                if (ids.add(chunk.getId())) {
                    mongoTemplate.save(chunk, collection);
                }
            }
            mongoTemplate.remove(Query.query(Criteria.where("_id").nin(ids)), ChatKnowledgeChunkEntity.class, collection);
            memory = null;
            if (retrieval != null) {
                retrieval.bumpVersion();
            }
            return ids.size();
        } finally {
            running.set(false);
        }
    }

    public List<KnowledgeHit> search(String text, String lang, String preferredKey, int limit) {
        String terms = terms(text);
        if (terms.isBlank() && preferredKey == null) {
            return List.of();
        }
        if (searcher != null) {
            try {
                List<KnowledgeHit> hits = searcher.search(terms, lang, preferredKey, limit);
                return hits == null ? List.of() : hits;
            } catch (RuntimeException e) {
                log.debug("[chatbot] knowledge search failed, using memory: {}", e.getMessage());
            }
        }
        return fromMemory(terms, lang, preferredKey, limit);
    }

    public List<KnowledgeHit> fromMemory(String terms, String lang, String preferredKey, int limit) {
        List<ChatKnowledgeChunkEntity> all = memoryChunks();
        List<String> stems = new ArrayList<>();
        for (String t : TextNormalizer.tokens(terms)) {
            stems.add(languages.stem(t));
        }
        List<KnowledgeHit> out = new ArrayList<>();
        for (ChatKnowledgeChunkEntity c : all) {
            if (!lang.equals(c.getLang())) {
                continue;
            }
            Set<String> chunkStems = new HashSet<>();
            for (String t : TextNormalizer.tokens(c.getTitle() + " " + c.getText())) {
                chunkStems.add(languages.stem(t));
            }
            double score = 0;
            for (String s : stems) {
                if (chunkStems.contains(s)) {
                    score += 1;
                }
            }
            if (preferredKey != null && preferredKey.equals(c.getSourceKey())) {
                score += 1;
            }
            if (score > 0) {
                out.add(new KnowledgeHit(c.getTitle(), c.getText(), c.getPath(), c.getSourceKey(), score));
            }
        }
        out.sort(Comparator.comparingDouble(KnowledgeHit::score).reversed());
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    private List<ChatKnowledgeChunkEntity> memoryChunks() {
        List<ChatKnowledgeChunkEntity> m = memory;
        if (m != null && System.currentTimeMillis() - memoryLoadedAt < MEMORY_TTL_MS) {
            return m;
        }
        try {
            m = mongoTemplate == null ? List.of() : mongoTemplate.findAll(ChatKnowledgeChunkEntity.class, collection);
            if (m.isEmpty()) {
                m = chunks();
            }
        } catch (RuntimeException e) {
            m = chunks();
        }
        memory = m;
        memoryLoadedAt = System.currentTimeMillis();
        return m;
    }

    public String terms(String text) {
        List<String> out = new ArrayList<>();
        for (String t : TextNormalizer.tokens(text)) {
            if (t.length() >= 3 && !languages.isStopword(t) && !out.contains(t)) {
                out.add(t);
            }
        }
        return String.join(" ", out);
    }

    public List<ChatKnowledgeChunkEntity> chunks() {
        List<ChatKnowledgeChunkEntity> out = new ArrayList<>();
        for (ChatKnowledgeSource source : sources) {
            List<KnowledgeDocument> docs;
            try {
                docs = source.documents(languages.languages());
            } catch (RuntimeException e) {
                log.warn("[chatbot] knowledge source {} failed: {}", source.getClass().getSimpleName(), e.getMessage());
                continue;
            }
            for (KnowledgeDocument doc : docs == null ? List.<KnowledgeDocument>of() : docs) {
                out.addAll(chunks(doc));
            }
        }
        return out;
    }

    public List<ChatKnowledgeChunkEntity> chunks(KnowledgeDocument doc) {
        List<ChatKnowledgeChunkEntity> out = new ArrayList<>();
        if (doc == null || doc.sections() == null) {
            return out;
        }
        int index = 0;
        for (KnowledgeChunker.Section section : doc.sections()) {
            String title = replace(section.title() == null ? doc.title() : doc.title() + " — " + section.title());
            String body = replace(doc.html() ? KnowledgeChunker.stripHtml(section.text()) : section.text());
            for (String piece : KnowledgeChunker.chunk(KnowledgeChunker.sanitize(body, guard), KnowledgeChunker.MAX_CHARS)) {
                out.add(chunk(doc.sourceType(), doc.sourceKey(), doc.lang(), title, piece, doc.path(), index++));
            }
        }
        return out;
    }

    private static ChatKnowledgeChunkEntity chunk(String type, String key, String lang, String title, String text,
                                                  String path, int index) {
        return ChatKnowledgeChunkEntity.builder()
                .id(stableId(type + "|" + key + "|" + lang + "|" + index))
                .sourceType(type)
                .sourceKey(key)
                .lang(lang)
                .title(title)
                .text(text)
                .path(path)
                .updatedAt(Instant.now())
                .build();
    }

    public static long stableId(String value) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            long v = 0;
            for (int i = 0; i < 6; i++) {
                v = (v << 8) | (d[i] & 0xff);
            }
            return v == 0 ? 1 : v;
        } catch (Exception e) {
            return Math.abs((long) value.hashCode()) + 1;
        }
    }
}
