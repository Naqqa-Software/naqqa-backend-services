package com.naqqa.chatbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.chatbot.config.NaqqaChatbotProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Slf4j
public class ChatSttService {

    private static final long FFMPEG_TIMEOUT_SECONDS = 15;
    private static final Map<String, String> LANGUAGE_CODES = languageCodes();
    private static final List<Pattern> HALLUCINATIONS = List.of(
            Pattern.compile("(?iuU)^\\W*\u0441\u0443\u0431\u0442\u0438\u0442\u0440\u044b (\u0441\u0434\u0435\u043b\u0430\u043b|\u0441\u043e\u0437\u0434\u0430\u0432\u0430\u043b|\u043f\u043e\u0434\u043e\u0433\u043d\u0430\u043b|\u0434\u0435\u043b\u0430\u043b).*"),
            Pattern.compile("(?iuU)^\\W*\u0440\u0435\u0434\u0430\u043a\u0442\u043e\u0440 \u0441\u0443\u0431\u0442\u0438\u0442\u0440\u043e\u0432.*"),
            Pattern.compile("(?iuU)^\\W*\u043f\u0440\u043e\u0434\u043e\u043b\u0436\u0435\u043d\u0438\u0435 \u0441\u043b\u0435\u0434\u0443\u0435\u0442\\W*$"),
            Pattern.compile("(?iuU)^\\W*(thanks|thank you) for watching\\W*$"),
            Pattern.compile("(?iuU).*\\b(dimatorzok|amara\\.org)\\b.*"));

    public record SttResult(String text, String language) {
    }

    private final NaqqaChatbotProperties properties;
    private final NaqqaChatbotProperties.Stt stt;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Semaphore permits;
    private volatile Boolean ffmpegAvailable;
    private volatile long unavailableUntil;

    public ChatSttService(NaqqaChatbotProperties properties) {
        this.properties = properties;
        this.stt = properties.getStt();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        long connect = stt.getConnectTimeoutMs() <= 0 ? 1_500L : stt.getConnectTimeoutMs();
        factory.setConnectTimeout((int) Math.min(stt.effectiveTimeoutMs(), connect));
        factory.setReadTimeout((int) stt.effectiveTimeoutMs());
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.permits = new Semaphore(Math.max(1, stt.getMaxConcurrent()), true);
    }

    public String sttMode() {
        return available() ? "server" : "browser";
    }

    public boolean available() {
        return properties.isServerStt() && System.currentTimeMillis() >= unavailableUntil;
    }

    public String transcribe(byte[] audio, String contentType, String lang, String clientTranscript) {
        String fallback = clean(clientTranscript);
        if (!properties.isServerStt()) {
            return fallback;
        }
        try {
            SttResult result = recognize(audio, contentType, lang);
            return result.text() == null || result.text().isBlank() ? fallback : result.text();
        } catch (Exception e) {
            return fallback;
        }
    }

    public SttResult recognize(byte[] audio, String contentType, String langHint) {
        if (!available()) {
            throw unavailable();
        }
        byte[] wav = isWav(contentType) ? audio : toWav(audio, contentType);
        if (wav == null) {
            throw unavailable();
        }
        boolean acquired;
        try {
            acquired = permits.tryAcquire(Math.max(0L, stt.getQueueWaitMs()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
        if (!acquired) {
            throw unavailable();
        }
        try {
            SttResult raw = whisper(wav, requestLanguage(langHint));
            String text = clean(raw.text());
            if (text != null && hallucination(text)) {
                text = null;
            }
            return new SttResult(text == null ? "" : text, raw.language());
        } catch (ResourceAccessException e) {
            unavailableUntil = System.currentTimeMillis() + Math.max(0L, stt.getCooldownMs());
            log.warn("Local STT unreachable: {}", e.getMessage());
            throw unavailable();
        } catch (ChatException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Local STT failed: {}", e.getMessage());
            throw unavailable();
        } finally {
            permits.release();
        }
    }

    private static ChatException unavailable() {
        return new ChatException(HttpStatus.SERVICE_UNAVAILABLE, ChatException.STT_UNAVAILABLE, "Server-side transcription is not available.");
    }

    private static boolean isWav(String contentType) {
        return contentType != null && "audio/wav".equals(contentType.split(";")[0].trim().toLowerCase(Locale.ROOT));
    }

    String requestLanguage(String langHint) {
        String configured = stt.normalizedLanguage();
        if ("client".equals(configured)) {
            String hint = langHint == null ? "" : langHint.trim().toLowerCase(Locale.ROOT);
            return hint.matches("[a-z]{2,3}") ? hint : "auto";
        }
        return configured;
    }

    private SttResult whisper(byte[] wav, String language) throws Exception {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(wav) {
            @Override
            public String getFilename() {
                return "audio.wav";
            }
        });
        form.add("language", language);
        form.add("response_format", "verbose_json");
        form.add("temperature", "0.0");
        form.add("temperature_inc", "0.2");
        String body = restClient.post()
                .uri(stt.normalizedWhisperUrl() + "/inference")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(String.class);
        JsonNode json = objectMapper.readTree(body == null ? "{}" : body);
        if (json.hasNonNull("error")) {
            throw new IllegalStateException("whisper error");
        }
        String detected = languageCode(json.path("language").asText(""));
        if (detected == null) {
            detected = languageCode(json.path("detected_language").asText(""));
        }
        if (detected == null && !"auto".equals(language)) {
            detected = language;
        }
        return new SttResult(json.path("text").asText(""), detected);
    }

    static String languageCode(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty() || "auto".equals(v)) {
            return null;
        }
        if (v.matches("[a-z]{2,3}")) {
            return v;
        }
        return LANGUAGE_CODES.get(v);
    }

    private static Map<String, String> languageCodes() {
        Map<String, String> codes = new HashMap<>();
        for (String code : Locale.getISOLanguages()) {
            String name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).toLowerCase(Locale.ROOT);
            if (!name.isEmpty() && !name.equals(code)) {
                codes.putIfAbsent(name, code);
            }
        }
        codes.put("moldavian", "ro");
        codes.put("moldovan", "ro");
        codes.put("romanian", "ro");
        codes.put("russian", "ru");
        codes.put("ukrainian", "uk");
        codes.put("english", "en");
        codes.put("castilian", "es");
        codes.put("spanish", "es");
        codes.put("flemish", "nl");
        codes.put("dutch", "nl");
        codes.put("haitian creole", "ht");
        codes.put("letzeburgesch", "lb");
        codes.put("pushto", "ps");
        codes.put("panjabi", "pa");
        codes.put("sinhalese", "si");
        codes.put("valencian", "ca");
        codes.put("burmese", "my");
        codes.put("mandarin", "zh");
        codes.put("chinese", "zh");
        return Map.copyOf(codes);
    }

    static boolean hallucination(String text) {
        for (Pattern pattern : HALLUCINATIONS) {
            if (pattern.matcher(text).matches()) {
                return true;
            }
        }
        return false;
    }

    public static String demuxer(String contentType) {
        if (contentType == null) {
            return null;
        }
        return switch (contentType) {
            case "audio/webm" -> "matroska";
            case "audio/ogg" -> "ogg";
            case "audio/mp4" -> "mov";
            case "audio/mpeg" -> "mp3";
            case "audio/wav" -> "wav";
            default -> null;
        };
    }

    private byte[] toWav(byte[] audio, String contentType) {
        String format = demuxer(contentType);
        if (format == null || !ffmpegAvailable()) {
            return null;
        }
        Path in = null;
        Path out = null;
        try {
            in = Files.createTempFile("naqqa-chat-in-", ".bin");
            out = Files.createTempFile("naqqa-chat-out-", ".wav");
            Files.write(in, audio);
            List<String> command = List.of(stt.normalizedFfmpegPath(), "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                    "-protocol_whitelist", "file", "-f", format,
                    "-i", in.toAbsolutePath().toString(), "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", "-f", "wav",
                    out.toAbsolutePath().toString());
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(FFMPEG_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("ffmpeg conversion timed out");
                return null;
            }
            if (process.exitValue() != 0) {
                log.warn("ffmpeg conversion failed with exit code {}", process.exitValue());
                return null;
            }
            byte[] wav = Files.readAllBytes(out);
            return wav.length > 44 ? wav : null;
        } catch (Exception e) {
            log.warn("ffmpeg conversion error: {}", e.getMessage());
            return null;
        } finally {
            deleteQuietly(in);
            deleteQuietly(out);
        }
    }

    private boolean ffmpegAvailable() {
        Boolean available = ffmpegAvailable;
        if (available != null) {
            return available;
        }
        synchronized (this) {
            if (ffmpegAvailable == null) {
                ffmpegAvailable = probeFfmpeg();
                if (!ffmpegAvailable) {
                    log.warn("ffmpeg not available at '{}'; only WAV voice input can be transcribed", stt.normalizedFfmpegPath());
                }
            }
            return ffmpegAvailable;
        }
    }

    private boolean probeFfmpeg() {
        String path = stt.normalizedFfmpegPath();
        if (path == null || path.isBlank()) {
            return false;
        }
        try {
            Process process = new ProcessBuilder(List.of(path, "-version"))
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception e) {
            File file = path.toFile();
            file.deleteOnExit();
        }
    }

    static String clean(String text) {
        if (text == null) {
            return null;
        }
        String value = text.replaceAll("\\[[^\\]]{0,40}\\]", " ").replaceAll("(?iu)\\(\\s*(music|muzic\u0103|\u043c\u0443\u0437\u044b\u043a\u0430)\\s*\\)", " ")
                .replaceAll("\\s+", " ").trim();
        if (value.length() > 500) {
            value = value.substring(0, 500);
        }
        return value.isEmpty() ? null : value;
    }
}
