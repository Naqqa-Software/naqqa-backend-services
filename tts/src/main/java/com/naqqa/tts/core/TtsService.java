package com.naqqa.tts.core;

import com.naqqa.tts.config.TtsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class TtsService {

    private static final Logger log = LoggerFactory.getLogger(TtsService.class);
    private static final String VERSION = "2";

    public record Audio(String key, byte[] bytes, boolean cached) {
    }

    public record Prepared(String key, String lang, String text, TtsProperties.Voice voice) {
    }

    private final TtsProperties properties;
    private final TtsEngine engine;
    private final TtsCache cache;
    private final TtsRateLimiter limiter;
    private final TtsTextNormalizer normalizer;
    private final TtsAudioProcessor audio;
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicLong openUntil = new AtomicLong();

    public TtsService(TtsProperties properties, TtsEngine engine, TtsCache cache, TtsRateLimiter limiter, TtsTextNormalizer normalizer) {
        this.properties = properties;
        this.engine = engine;
        this.cache = cache;
        this.limiter = limiter;
        this.normalizer = normalizer;
        this.audio = new TtsAudioProcessor(properties.getAudio());
    }

    public Set<String> languages() {
        return properties.getVoices().keySet();
    }

    public boolean ready() {
        return properties.isEnabled() && engine.ready() && System.currentTimeMillis() >= openUntil.get();
    }

    public Prepared prepare(String text, String lang) {
        if (!properties.isEnabled()) {
            throw new TtsException(TtsException.Reason.UNAVAILABLE, "TTS is disabled");
        }
        String l = lang == null || lang.isBlank() ? "ro" : lang.trim().toLowerCase(Locale.ROOT);
        TtsProperties.Voice voice = properties.getVoices().get(l);
        if (voice == null) {
            throw new TtsException(TtsException.Reason.UNSUPPORTED, "No voice for language " + l);
        }
        if (text == null || text.isBlank() || text.length() > properties.getMaxChars() * 4) {
            throw new TtsException(TtsException.Reason.INVALID, "Text is empty or too long");
        }
        Map<String, String> lexicon = properties.getLexicon().getOrDefault(l, Map.of());
        String clean = normalizer.normalize(text, l, lexicon, properties.getMaxChars());
        if (clean.isBlank()) {
            throw new TtsException(TtsException.Reason.INVALID, "Nothing to speak");
        }
        String settings = voice.id() + "|" + properties.getPiper().getSentenceSilence() + "|" + properties.getAudio().id();
        return new Prepared(hash(VERSION + "|" + l + "|" + settings + "|" + clean), l, clean, voice);
    }

    public Audio speak(Prepared prepared, String clientKey) {
        Optional<byte[]> hit = cache.get(prepared.key());
        if (hit.isPresent()) {
            return new Audio(prepared.key(), hit.get(), true);
        }
        if (System.currentTimeMillis() < openUntil.get()) {
            throw new TtsException(TtsException.Reason.UNAVAILABLE, "TTS is cooling down after failures");
        }
        if (!engine.ready()) {
            engine.prepare();
            throw new TtsException(TtsException.Reason.UNAVAILABLE, "TTS engine is not ready yet");
        }
        if (!limiter.allow(clientKey)) {
            throw new TtsException(TtsException.Reason.RATE_LIMITED, "Too many speech requests");
        }
        Path temp = null;
        try {
            temp = cache.tempFile(prepared.key());
            Path out = engine.synthesize(prepared.text(), prepared.lang(), prepared.voice(), temp);
            if (properties.getAudio().isEnabled()) {
                Files.write(out, audio.process(Files.readAllBytes(out)));
            }
            byte[] bytes = cache.put(prepared.key(), out);
            failures.set(0);
            return new Audio(prepared.key(), bytes, false);
        } catch (TtsException e) {
            if (e.reason() == TtsException.Reason.FAILED) {
                recordFailure(e);
            }
            throw e;
        } catch (IOException e) {
            recordFailure(e);
            throw new TtsException(TtsException.Reason.FAILED, "Speech could not be stored", e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    log.debug("TTS temp file {} could not be deleted", temp);
                }
            }
        }
    }

    private void recordFailure(Exception e) {
        int count = failures.incrementAndGet();
        log.warn("TTS synthesis failed ({} in a row): {}", count, e.getMessage());
        if (count >= Math.max(1, properties.getBreakerFailures())) {
            openUntil.set(System.currentTimeMillis() + properties.getBreakerOpenMs());
            failures.set(0);
        }
    }

    static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 40);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
