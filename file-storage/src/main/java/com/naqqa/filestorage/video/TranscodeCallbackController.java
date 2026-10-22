package com.naqqa.filestorage.video;

import com.naqqa.filestorage.config.FileStorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@ConditionalOnProperty(prefix = "gcs.lib.transcode", name = "expose-callback-endpoint",
        havingValue = "true", matchIfMissing = true)
public class TranscodeCallbackController {

    private static final Logger log = LoggerFactory.getLogger(TranscodeCallbackController.class);

    private final FileStorageProperties props;
    private final ObjectProvider<TranscodeResultHandler> handler;

    public TranscodeCallbackController(FileStorageProperties props,
                                       ObjectProvider<TranscodeResultHandler> handler) {
        this.props = props;
        this.handler = handler;
    }

    public static final String TOKEN_HEADER = "X-Transcode-Token";

    public record CallbackRequest(String groupId, String status, String detail) {}

    @PostMapping("${gcs.lib.transcode.callback-path:/api/internal/transcode/callback}")
    public ResponseEntity<Void> callback(
            @RequestHeader(value = TOKEN_HEADER, required = false) String presentedToken,
            @RequestBody CallbackRequest body
    ) {
        if (!authorized(presentedToken)) {
            log.warn("Rejected transcode callback for '{}'", body != null ? body.groupId() : null);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (body == null || body.groupId() == null || body.status() == null) {
            return ResponseEntity.badRequest().build();
        }

        TranscodeResultHandler target = handler.getIfAvailable();
        if (target == null) {
            log.error("Transcode callback for '{}' has no TranscodeResultHandler to deliver to; "
                    + "define one to consume transcode results", body.groupId());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        target.onTranscodeComplete(body.groupId(), "READY".equals(body.status()), body.detail());
        return ResponseEntity.accepted().build();
    }

    private boolean authorized(String presentedToken) {
        String expected = props.getTranscode().getCallbackToken();
        if (expected == null || expected.isBlank() || presentedToken == null) {
            return false;
        }
        return MessageDigest.isEqual(
                presentedToken.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
