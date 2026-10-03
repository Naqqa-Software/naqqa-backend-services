package com.naqqa.tts.web;

import com.naqqa.tts.config.TtsProperties;
import com.naqqa.tts.core.TtsException;
import com.naqqa.tts.core.TtsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
public class TtsController {

    private static final MediaType WAV = MediaType.parseMediaType("audio/wav");

    private final TtsService service;
    private final TtsProperties properties;

    public TtsController(TtsService service, TtsProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("${naqqa.tts.path:/api/public/tts}")
    public ResponseEntity<byte[]> speak(@RequestParam("text") String text,
                                        @RequestParam(value = "lang", required = false) String lang,
                                        @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
                                        HttpServletRequest request) {
        TtsService.Prepared prepared = service.prepare(text, lang);
        String etag = "\"" + prepared.key() + "\"";
        CacheControl cache = CacheControl.maxAge(properties.getCacheMaxAgeSeconds(), TimeUnit.SECONDS).cachePublic();
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(cache).build();
        }
        TtsService.Audio audio = service.speak(prepared, clientKey(request));
        return ResponseEntity.ok()
                .contentType(WAV)
                .eTag(etag)
                .cacheControl(cache)
                .header("X-TTS-Cache", audio.cached() ? "hit" : "miss")
                .body(audio.bytes());
    }

    @GetMapping("${naqqa.tts.path:/api/public/tts}/status")
    public Map<String, Object> status() {
        return Map.of("enabled", properties.isEnabled(), "ready", service.ready(), "languages", service.languages());
    }

    @ExceptionHandler(TtsException.class)
    public ResponseEntity<Map<String, String>> failed(TtsException e) {
        HttpStatus status = switch (e.reason()) {
            case INVALID, UNSUPPORTED -> HttpStatus.BAD_REQUEST;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case BUSY, UNAVAILABLE, FAILED -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status).cacheControl(CacheControl.noStore());
        if (status == HttpStatus.SERVICE_UNAVAILABLE || status == HttpStatus.TOO_MANY_REQUESTS) {
            builder.header(HttpHeaders.RETRY_AFTER, e.reason() == TtsException.Reason.BUSY ? "2" : "30");
        }
        return builder.body(Map.of("error", e.reason().name()));
    }

    static String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        return request.getRemoteAddr();
    }
}
