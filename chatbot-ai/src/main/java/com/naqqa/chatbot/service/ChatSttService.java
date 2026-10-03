package com.naqqa.chatbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.chatbot.config.NaqqaChatbotProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ChatSttService {

    private static final long FFMPEG_TIMEOUT_SECONDS = 15;

    private final NaqqaChatbotProperties properties;
    private final NaqqaChatbotProperties.Stt stt;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile Boolean ffmpegAvailable;

    public ChatSttService(NaqqaChatbotProperties properties) {
        this.properties = properties;
        this.stt = properties.getStt();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Math.min(stt.effectiveTimeoutMs(), 5_000L));
        factory.setReadTimeout((int) stt.effectiveTimeoutMs());
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public String sttMode() {
        return properties.isServerStt() ? "server" : "browser";
    }

    public String transcribe(byte[] audio, String contentType, String lang, String clientTranscript) {
        String fallback = clean(clientTranscript);
        if (!properties.isServerStt()) {
            return fallback;
        }
        try {
            byte[] wav = "audio/wav".equals(contentType) ? audio : toWav(audio, contentType);
            if (wav == null) {
                return fallback;
            }
            String text = whisper(wav, lang);
            return text == null || text.isBlank() ? fallback : clean(text);
        } catch (Exception e) {
            log.warn("Local STT failed: {}", e.getMessage());
            return fallback;
        }
    }

    private String whisper(byte[] wav, String lang) throws Exception {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(wav) {
            @Override
            public String getFilename() {
                return "audio.wav";
            }
        });
        form.add("language", lang == null || lang.isBlank() ? "auto" : lang);
        form.add("response_format", "json");
        form.add("temperature", "0.0");
        String body = restClient.post()
                .uri(stt.normalizedWhisperUrl() + "/inference")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(String.class);
        JsonNode json = objectMapper.readTree(body == null ? "{}" : body);
        return json.path("text").asText("");
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
                    log.warn("ffmpeg not available at '{}'; non-WAV voice messages will use the client transcript", stt.normalizedFfmpegPath());
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
        String value = text.replaceAll("\\[[^\\]]{0,40}\\]", " ").replaceAll("\\s+", " ").trim();
        if (value.length() > 500) {
            value = value.substring(0, 500);
        }
        return value.isEmpty() ? null : value;
    }
}
