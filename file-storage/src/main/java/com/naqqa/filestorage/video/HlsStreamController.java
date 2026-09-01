package com.naqqa.filestorage.video;

import com.google.cloud.ReadChannel;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.naqqa.filestorage.config.FileStorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * Serves an HLS ladder — master playlist, per-rung playlists and fMP4 segments — under the
 * application's own {@link HlsAccessPolicy}.
 *
 * <p>Objects are addressed by their path relative to the ladder prefix, not by file id: a ladder is
 * hundreds of objects that were never registered as {@code FileEntity} rows. Access is therefore
 * checked once per <em>ladder</em> and memoised, rather than resolved per object.
 *
 * <p>ffmpeg emits relative references throughout (master.m3u8 -> {@code 1080p/index.m3u8} ->
 * {@code seg_00001.m4s}), so proxying by relative path needs no manifest rewriting — the player
 * resolves each against this endpoint.
 *
 * <p>No {@code Range} handling: segments are small whole-object fetches. That is also what keeps
 * this affordable without a CDN — each request is short, releasing its server slot in about a second
 * instead of holding one open for the length of the video. It sidesteps request-timeout limits that
 * progressive streaming of a long file can hit, too.
 */
@RestController
@ConditionalOnProperty(prefix = "gcs.lib.hls", name = "enabled", havingValue = "true")
public class HlsStreamController {

    private static final Logger log = LoggerFactory.getLogger(HlsStreamController.class);
    private static final int BUFFER_SIZE = 64 * 1024;

    private final Storage storage;
    private final FileStorageProperties props;
    private final HlsAccessCache accessCache;
    private final VideoTranscodeService transcodeService;

    /**
     * {@code HlsAccessPolicy} is required, not optional: enabling HLS without one would mean serving
     * private ladders with no authorisation at all. Failing at startup with a clear message beats
     * discovering it from an open endpoint.
     */
    public HlsStreamController(Storage storage, FileStorageProperties props,
                               HlsAccessPolicy accessPolicy, VideoTranscodeService transcodeService) {
        this.storage = storage;
        this.props = props;
        this.accessCache = new HlsAccessCache(accessPolicy, props);
        this.transcodeService = transcodeService;
    }

    @GetMapping("${gcs.lib.hls.base-path:/api/media}/{groupId}/hls/**")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<StreamingResponseBody> get(@PathVariable String groupId,
                                                     Authentication authentication,
                                                     HttpServletRequest request) {
        String relativePath = extractRelativePath(request, groupId);
        if (relativePath == null) {
            return ResponseEntity.badRequest().build();
        }
        if (!accessCache.canStream(groupId, authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String objectName = transcodeService.ladderPrefix(groupId) + "/" + relativePath;
        Blob blob = storage.get(BlobId.of(props.getBucketName(), objectName));
        if (blob == null || !blob.exists()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, contentTypeFor(relativePath))
                // Segments are immutable; playlists are not, because adding a rung rewrites the
                // master. "private" because these responses are authorised per user, so no shared
                // cache may retain them.
                .header(HttpHeaders.CACHE_CONTROL, relativePath.endsWith(".m3u8")
                        ? "private, max-age=60"
                        : "private, max-age=31536000, immutable")
                .contentLength(blob.getSize())
                .body(out -> copy(blob, out));
    }

    /**
     * Pulls the ladder-relative path out of the request and rejects anything trying to escape the
     * prefix. The result is concatenated onto an object name, so a {@code ..} segment would
     * otherwise read arbitrary objects out of the bucket.
     */
    private String extractRelativePath(HttpServletRequest request, String groupId) {
        String full = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        if (full == null) {
            full = request.getRequestURI();
        }
        String marker = "/" + groupId + "/hls/";
        int idx = full.indexOf(marker);
        if (idx < 0) {
            return null;
        }
        String relative = full.substring(idx + marker.length());
        if (relative.isBlank()
                || relative.contains("..")
                || relative.startsWith("/")
                || relative.contains("//")
                || relative.contains("\\")) {
            log.warn("Rejected HLS path traversal attempt for '{}': {}", groupId, relative);
            return null;
        }
        return relative;
    }

    private static String contentTypeFor(String path) {
        if (path.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
        if (path.endsWith(".m4s"))  return "video/iso.segment";
        if (path.endsWith(".mp4"))  return "video/mp4";
        if (path.endsWith(".ts"))   return "video/mp2t";
        return "application/octet-stream";
    }

    private void copy(Blob blob, OutputStream out) throws IOException {
        try (ReadChannel reader = blob.reader()) {
            ByteBuffer buf = ByteBuffer.allocate(BUFFER_SIZE);
            int read;
            while ((read = reader.read(buf)) > 0) {
                out.write(buf.array(), 0, read);
                buf.clear();
            }
        } catch (Exception e) {
            throw new IOException("Failed to stream HLS object from GCS", e);
        }
    }
}
