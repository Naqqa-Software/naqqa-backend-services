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

/**
 * Receives the transcode job's result and hands it to the application's {@link TranscodeResultHandler}.
 *
 * <p>The job has no user session, so this sits outside any JWT chain and authenticates with a shared
 * secret instead. When {@code gcs.lib.transcode.callback-token} is blank it refuses every request
 * rather than defaulting to open — an unauthenticated caller here could mark any ladder ready and
 * point viewers at objects that do not exist.
 *
 * <p>Registration is controlled by {@code expose-callback-endpoint} alone (default on); an
 * application wanting its own endpoint sets it false. The {@link TranscodeResultHandler} is resolved
 * lazily and may be absent — an application that scans this library but does no transcoding of its
 * own must still start. Requiring the handler through the constructor meant it could not: the
 * context failed with an unsatisfied dependency before anything else could report a problem.
 *
 * <p>Resolved through {@code ObjectProvider} rather than gated with {@code @ConditionalOnBean},
 * which is only dependable inside auto-configuration. On a component-scanned bean it is evaluated
 * mid-scan, when a handler defined by the application may not be registered yet — so it could just
 * as easily have removed the endpoint from an application that does have one, leaving finished
 * transcodes with nowhere to report and ladders stuck forever.
 *
 * <p>Remember to permit {@code gcs.lib.transcode.callback-path} in the application's security
 * configuration; it is authenticated by the shared secret, not by the filter chain.
 */
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

    /**
     * Deliberately NOT {@code Authorization: Bearer}.
     *
     * <p>Applications protected by an OAuth2 resource server authenticate any request carrying a
     * Bearer token, so a shared secret sent that way is parsed as a JWT, fails, and is rejected with
     * 401 before this controller is ever reached — even on a permitted path. A dedicated header
     * sidesteps the JWT filter entirely.
     */
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
            // Authenticated but undeliverable. 503 rather than 200, so a caller that retries — the
            // transcode job does — is told to, instead of recording a result nothing consumed.
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
        // Constant-time compare so the token cannot be recovered by timing the endpoint.
        return MessageDigest.isEqual(
                presentedToken.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
