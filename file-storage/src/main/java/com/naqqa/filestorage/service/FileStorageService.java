package com.naqqa.filestorage.service;

import com.google.cloud.storage.*;
import com.naqqa.filestorage.config.FileStorageProperties;
import com.naqqa.filestorage.entities.FileEntity;
import com.naqqa.filestorage.enums.FileAccessEnum;
import com.naqqa.filestorage.exceptions.GCPFileException;
import com.naqqa.filestorage.model.InitiateUploadResponse;
import com.naqqa.filestorage.repository.FileRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p><b>Transaction scope is deliberately per-method.</b> A class-level {@code @Transactional} held a
 * pooled database connection for the whole of every upload — across the GCS round-trip — so a handful
 * of concurrent large uploads could exhaust the pool while doing almost no database work. Only the
 * methods that genuinely need atomicity declare it; single-row saves do not need a transaction, and
 * when these are called from inside a caller's transaction they still join it.
 */
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileStorageService.class);

    private final FileRepository fileRepository;
    private final Storage storage;
    private final FileStorageProperties props;

    /**
     * Normalises a caller-supplied directory into a safe key prefix.
     *
     * <p>The directory is concatenated straight into the object key, so {@code ..} segments, leading
     * slashes and backslashes would let a caller write outside the intended prefix — and, where a
     * public bucket is configured, place an object under the public prefix and have it served
     * world-readable. Rejecting is deliberate: silently rewriting a path hides an attempted escape.
     */
    protected String sanitizeDirectory(String directory) {
        if (directory == null || directory.isBlank()) {
            return "general";
        }
        String cleaned = directory.trim().replace('\\', '/');
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        while (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.isBlank() || cleaned.contains("..") || cleaned.contains("//")
                || cleaned.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid upload directory: " + directory);
        }
        return cleaned;
    }

    /** Strips any path component from a caller-supplied filename before it enters the key. */
    protected String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "file";
        }
        String base = fileName.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.trim();
        return base.isBlank() || base.equals(".") || base.equals("..") ? "file" : base;
    }

    // ------------------ RESUMABLE UPLOAD (For Background/Large Files) ------------------
    public InitiateUploadResponse startResumableSession(String directory, String fileName, String contentType, Long ownerId) {
        String objectKey = sanitizeDirectory(directory) + "/" + System.currentTimeMillis() + "_" + sanitizeFileName(fileName);

        // 1. Create record in PENDING state
        FileEntity entity = new FileEntity();
        entity.setFileName(objectKey);
        entity.setOriginalFileName(fileName);
        entity.setContentType(contentType);
        entity.setOwnerId(ownerId);
        entity.setStatus("PENDING");
        FileEntity saved = fileRepository.save(entity);

        // 2. Generate GCS URL
        String url = initiateResumableUploadByKey(objectKey, contentType);

        return new InitiateUploadResponse(url, saved.getId());
    }

    /**
     * Returns the URL the client uploads to.
     *
     * <p>Defaults to a genuine GCS <b>resumable session URI</b>, which is what makes large uploads
     * survivable: the client can send the object in chunks and resume from the last acknowledged
     * byte after a dropped connection, and GCS keeps the session valid for a week. The previous
     * behaviour — a single signed PUT valid for one hour — put a hard ceiling on upload size,
     * because any transfer slower than the window expired part-way through and had to restart from
     * zero.
     *
     * <p>{@code SIGNED_PUT} keeps that older behaviour for callers that prefer its simplicity.
     */
    private String initiateResumableUploadByKey(String objectKey, String contentType) {
        return props.getUpload().getMode() == FileStorageProperties.Upload.Mode.RESUMABLE
                ? startGcsResumableSession(objectKey, contentType)
                : signedPutUrl(objectKey, contentType);
    }

    private String signedPutUrl(String objectKey, String contentType) {
        BlobId blobId = BlobId.of(props.getBucketName(), objectKey);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType(contentType).build();

        URL signedUrl = storage.signUrl(blobInfo,
                props.getUpload().getSignedUrlExpiryMinutes(), TimeUnit.MINUTES,
                Storage.SignUrlOption.withV4Signature(),
                Storage.SignUrlOption.httpMethod(HttpMethod.PUT)
        );
        return signedUrl.toString();
    }

    /**
     * Opens a resumable session with GCS and returns its session URI.
     *
     * <p>The URI itself is the capability — anyone holding it can write to that one object until it
     * expires — so it is handed to the client exactly as a signed URL would be, and no credentials
     * ever leave the server.
     *
     * <p>Note the client must have CORS permission for {@code PUT} on the bucket, and the bucket
     * must expose the {@code Location} and {@code Range} response headers, or the browser cannot
     * read the progress GCS reports back.
     */
    private String startGcsResumableSession(String objectKey, String contentType) {
        try {
            com.google.auth.Credentials credentials = storage.getOptions().getCredentials();
            if (!(credentials instanceof com.google.auth.oauth2.OAuth2Credentials oauth)) {
                throw new IllegalStateException(
                        "Resumable uploads need OAuth2 credentials; got " + credentials.getClass().getName());
            }
            oauth.refreshIfExpired();
            String accessToken = oauth.getAccessToken().getTokenValue();

            // Build a java.net.URI, not a String. RestClient encodes a String uri, so an
            // already-encoded key was encoded a second time — every "/" became "%252F" and the
            // object was created with a flat name containing literal "%2F" instead of a path.
            String encodedName = java.net.URLEncoder
                    .encode(objectKey, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20");
            java.net.URI uri = java.net.URI.create(
                    "https://storage.googleapis.com/upload/storage/v1/b/"
                            + java.net.URLEncoder.encode(props.getBucketName(), java.nio.charset.StandardCharsets.UTF_8)
                            + "/o?uploadType=resumable&name=" + encodedName);

            String effectiveType = (contentType == null || contentType.isBlank())
                    ? "application/octet-stream" : contentType;

            var request = org.springframework.web.client.RestClient.create()
                    .post()
                    .uri(uri)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("X-Upload-Content-Type", effectiveType)
                    .header("Content-Type", "application/json; charset=UTF-8");

            // Bind the session to the browser's origin.
            //
            // GCS decides CORS for a resumable session when the session is CREATED, not on the
            // uploads that follow. Initiating from the server with no Origin produces a session that
            // answers every later PUT with 200 but no Access-Control-Allow-Origin — so the browser
            // reports a CORS failure on a request that actually succeeded.
            String origin = callerOrigin();
            if (origin != null) {
                request = request.header("Origin", origin);
            }

            org.springframework.http.ResponseEntity<Void> response = request
                    .body("{}")
                    .retrieve()
                    .toBodilessEntity();

            java.net.URI location = response.getHeaders().getLocation();
            if (location == null) {
                throw new IllegalStateException("GCS did not return a resumable session URI");
            }
            return location.toString();
        } catch (Exception e) {
            LOGGER.error("Failed to start resumable session for {}", objectKey, e);
            throw new GCPFileException("Failed to start resumable upload session: " + e.getMessage(), e);
        }
    }


    /**
     * Step 2: Finalizes the record in the database once the frontend confirms GCS upload is done.
     */
    public FileEntity finalizeUpload(Long fileId, Long size, Long requesterId) {
        return finalizeUpload(fileId, size, requesterId, false);
    }

    @Transactional
    public FileEntity finalizeUpload(Long fileId, Long size, Long requesterId, boolean isAdmin) {
        FileEntity entity = getFileById(fileId);

        if (!isAdmin && !java.util.Objects.equals(entity.getOwnerId(), requesterId)) {
            throw new AccessDeniedException("Unauthorized: You do not own this file session.");
        }

        // Trust the bucket, not the caller. This endpoint used to accept whatever size the client
        // reported and mark the row COMPLETED without checking anything had been uploaded — so a
        // client could finalize a session it never used, leaving a COMPLETED row pointing at no
        // object, with a size of its choosing.
        Blob blob = storage.get(BlobId.of(props.getBucketName(), entity.getFileName()));
        if (blob == null || !blob.exists()) {
            throw new GCPFileException("Cannot finalize " + fileId + ": no object at " + entity.getFileName());
        }

        Long actualSize = blob.getSize();
        if (size != null && actualSize != null && !size.equals(actualSize)) {
            LOGGER.warn("Finalize size mismatch for file {}: client reported {}, bucket has {}",
                    fileId, size, actualSize);
        }
        entity.setSize(actualSize != null ? actualSize : size);
        entity.setStatus("COMPLETED");
        return fileRepository.save(entity);
    }

    // ------------------ STANDARD UPLOAD METHODS ------------------

    public FileEntity uploadFile(MultipartFile file, String directory, Long ownerId) {
        if (file.isEmpty()) throw new IllegalArgumentException("File is empty");

        try {
            String objectKey = sanitizeDirectory(directory) + "/" + System.currentTimeMillis() + "_"
                    + sanitizeFileName(file.getOriginalFilename());
            BlobId blobId = BlobId.of(props.getBucketName(), objectKey);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                    .setContentType(file.getContentType())
                    .build();

            // Stream rather than file.getBytes(): that buffered the entire upload in heap, so a
            // few concurrent large files could exhaust it. createFrom streams in chunks.
            try (InputStream in = file.getInputStream()) {
                storage.createFrom(blobInfo, in);
            }

            FileEntity fileEntity = new FileEntity();
            fileEntity.setFileName(objectKey);
            fileEntity.setOriginalFileName(file.getOriginalFilename());
            fileEntity.setContentType(file.getContentType());
            fileEntity.setSize(file.getSize());
            fileEntity.setOwnerId(ownerId);
            fileEntity.setStatus("COMPLETED");

            return fileRepository.save(fileEntity);
        } catch (Exception e) {
            LOGGER.error("Error uploading file to GCS", e);
            throw new GCPFileException("Failed to upload file to GCS: " + e.getMessage(), e);
        }
    }

    public FileEntity uploadFileInputStream(InputStream inputStream, String fileName, String contentType, String directory, Long ownerId) {
        if (inputStream == null) throw new IllegalArgumentException("InputStream is null");

        String mimeType = (contentType == null || contentType.isBlank()) ? "application/octet-stream" : contentType;

        try {
            String objectKey = sanitizeDirectory(directory) + "/" + System.currentTimeMillis() + "_"
                    + sanitizeFileName(fileName);
            BlobId blobId = BlobId.of(props.getBucketName(), objectKey);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType(mimeType).build();

            Blob blob = storage.createFrom(blobInfo, inputStream);

            FileEntity fileEntity = new FileEntity();
            fileEntity.setFileName(objectKey);
            fileEntity.setOriginalFileName(fileName);
            fileEntity.setContentType(mimeType);
            fileEntity.setSize(blob.getSize());
            fileEntity.setOwnerId(ownerId);
            fileEntity.setStatus("COMPLETED");

            return fileRepository.save(fileEntity);
        } catch (Exception e) {
            LOGGER.error("Error uploading InputStream to GCS", e);
            throw new GCPFileException("Failed to upload stream to GCS: " + e.getMessage(), e);
        }
    }

    // ------------------ RETRIEVAL & DELETE ------------------

    public FileEntity getFileById(Long fileId) {
        return fileRepository.findById(fileId)
                .orElseThrow(() -> new GCPFileException("File not found with ID: " + fileId));
    }

    @Transactional
    public void deleteFile(Long fileId, Long requestUserId, boolean isAdmin) {
        FileEntity fileEntity = getFileById(fileId);

        // Objects.equals, not getOwnerId().equals: a file uploaded by a job rather than a person has
        // no owner, and dereferencing that turned an ordinary "not yours" refusal into a 500.
        if (!isAdmin && !java.util.Objects.equals(fileEntity.getOwnerId(), requestUserId)) {
            LOGGER.error("Unauthorized delete attempt! User {} tried to delete file {} owned by {}",
                    requestUserId, fileId, fileEntity.getOwnerId());
            throw new AccessDeniedException("You do not have permission to delete this file.");
        }

        BlobId blobId = BlobId.of(props.getBucketName(), fileEntity.getFileName());
        String objectName = fileEntity.getFileName();

        // Queue the row delete; do NOT flush it here.
        //
        // Callers routinely delete a file that something still references — replacing a lesson video
        // deletes the old one while the lesson row still points at it. Left to Hibernate, the UPDATE
        // that repoints the reference is ordered before the DELETE and everything is fine. Forcing
        // the DELETE out early instead hits the foreign key and fails the whole request.
        fileRepository.delete(fileEntity);

        // Remove the object only once the transaction has actually committed. Deleting it inline
        // would destroy the bytes even if the caller later rolled back, leaving a surviving row
        // pointing at nothing — unrecoverable. After commit, the worst case is an orphaned object,
        // which is recoverable and which the garbage collector already sweeps up.
        runAfterCommit(() -> deleteObject(blobId, objectName));
        LOGGER.info("Database record queued for deletion, file ID: {}", fileId);
    }

    /**
     * Runs {@code action} after the current transaction commits, or immediately when there is none.
     */
    private void runAfterCommit(Runnable action) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        action.run();
                    }
                });
    }

    private void deleteObject(BlobId blobId, String objectName) {
        try {
            if (storage.delete(blobId)) {
                LOGGER.info("File {} successfully deleted from GCS.", objectName);
            } else {
                LOGGER.warn("File {} was not found in GCS; database record removed anyway.", objectName);
            }
        } catch (Exception e) {
            // The row is already gone and the transaction has committed, so there is nothing to
            // undo. The object is now an orphan and is logged as one.
            LOGGER.error("Orphaned object: failed to delete {} from GCS after removing its row: {}",
                    objectName, e.getMessage());
        }
    }

    /**
     * The origin of the browser that asked for this upload.
     *
     * <p>Taken from the incoming request so development and production work without separate
     * configuration, since a session can only be bound to one origin. Falls back to a configured
     * value for callers with no web request (a scheduled job, a test).
     */
    private String callerOrigin() {
        try {
            var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes servlet) {
                String origin = servlet.getRequest().getHeader("Origin");
                if (origin != null && !origin.isBlank()) {
                    return origin;
                }
            }
        } catch (Exception ignored) {
            // No web request in scope; fall through to the configured origin.
        }
        String configured = props.getUpload().getBrowserOrigin();
        return (configured == null || configured.isBlank()) ? null : configured;
    }

    /**
     * Whether the object this row points at is actually present in the bucket.
     *
     * <p>Rows and objects can drift apart — a delete that half-failed, a bucket restored from an
     * older snapshot, an object removed out-of-band. Callers use this to tell "the user has no file"
     * from "the row is a tombstone", so it answers {@code false} rather than throwing on any error.
     */
    public boolean existsInStorage(FileEntity file) {
        if (file == null || file.getFileName() == null) {
            return false;
        }
        try {
            Blob blob = storage.get(BlobId.of(props.getBucketName(), file.getFileName()));
            return blob != null && blob.exists();
        } catch (Exception e) {
            LOGGER.warn("Failed to check existence of file {} in GCS: {}", file.getFileName(), e.getMessage());
            return false;
        }
    }

    public String getFileUrl(FileEntity file, FileAccessEnum access) {
        if (file == null || file.getFileName() == null) {
            return null;
        }
        int expiryMinutes = (access == FileAccessEnum.PRIVATE) ? 60 : 60 * 24 * 7;
        return generateSignedUrl(file.getFileName(), expiryMinutes);
    }

    private String generateSignedUrl(String objectKey, int durationMinutes) {
        try {
            BlobId blobId = BlobId.of(props.getBucketName(), objectKey);
            return storage.signUrl(
                    BlobInfo.newBuilder(blobId).build(),
                    durationMinutes,
                    TimeUnit.MINUTES,
                    Storage.SignUrlOption.withV4Signature()
            ).toString();
        } catch (Exception e) {
            LOGGER.error("Failed to generate signed URL for {}", objectKey, e);
            throw new GCPFileException("Failed to generate signed URL for file: " + objectKey, e);
        }
    }

    @Transactional
    public void markFilesAsLinked(List<Long> fileIds) {
        List<FileEntity> files = fileRepository.findAllById(fileIds);
        files.forEach(f -> f.setLinked(true));
        fileRepository.saveAll(files);
    }
}