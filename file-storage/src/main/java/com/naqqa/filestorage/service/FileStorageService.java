package com.naqqa.filestorage.service;

import com.google.cloud.ReadChannel;
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

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class FileStorageService {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileStorageService.class);

    private final FileRepository fileRepository;
    private final Storage storage;
    private final FileStorageProperties props;

    private static final int SNIFF_BYTES = 512;

    private BlobInfo blobInfo(String objectKey, ContentTypePolicy.Decision decision) {
        BlobInfo.Builder b = BlobInfo.newBuilder(BlobId.of(props.getBucketName(), objectKey))
                .setContentType(decision.contentType());
        if (decision.attachment()) {
            b.setContentDisposition("attachment");
        }
        return b.build();
    }

    private byte[] storedHead(BlobId blobId) {
        try (ReadChannel reader = storage.reader(blobId)) {
            if (reader == null) {
                return null;
            }
            ByteBuffer buf = ByteBuffer.allocate(SNIFF_BYTES);
            int n;
            do {
                n = reader.read(buf);
            } while (n >= 0 && buf.hasRemaining());
            return Arrays.copyOf(buf.array(), buf.position());
        } catch (Exception e) {
            LOGGER.warn("Could not read the start of {} to check its type", blobId.getName(), e);
            return null;
        }
    }

    private static BufferedInputStream sniffable(InputStream in) {
        BufferedInputStream buffered = new BufferedInputStream(in, SNIFF_BYTES * 2);
        buffered.mark(SNIFF_BYTES);
        return buffered;
    }

    private static byte[] head(BufferedInputStream in) throws java.io.IOException {
        byte[] head = in.readNBytes(SNIFF_BYTES);
        in.reset();
        return head;
    }

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

    public InitiateUploadResponse startResumableSession(String directory, String fileName, String contentType, Long ownerId) {
        String objectKey = sanitizeDirectory(directory) + "/" + System.currentTimeMillis() + "_" + sanitizeFileName(fileName);
        contentType = ContentTypePolicy.decide(contentType, null).contentType();

        FileEntity entity = new FileEntity();
        entity.setFileName(objectKey);
        entity.setOriginalFileName(fileName);
        entity.setContentType(contentType);
        entity.setOwnerId(ownerId);
        entity.setStatus("PENDING");
        FileEntity saved = fileRepository.save(entity);

        String url = initiateResumableUploadByKey(objectKey, contentType);

        return new InitiateUploadResponse(url, saved.getId());
    }

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
                Storage.SignUrlOption.httpMethod(HttpMethod.PUT),
                Storage.SignUrlOption.withExtHeaders(Map.of("Content-Type", contentType))
        );
        return signedUrl.toString();
    }

    private String startGcsResumableSession(String objectKey, String contentType) {
        try {
            com.google.auth.Credentials credentials = storage.getOptions().getCredentials();
            if (!(credentials instanceof com.google.auth.oauth2.OAuth2Credentials oauth)) {
                throw new IllegalStateException(
                        "Resumable uploads need OAuth2 credentials; got " + credentials.getClass().getName());
            }
            oauth.refreshIfExpired();
            String accessToken = oauth.getAccessToken().getTokenValue();

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


    public FileEntity finalizeUpload(Long fileId, Long size, Long requesterId) {
        return finalizeUpload(fileId, size, requesterId, false);
    }

    @Transactional
    public FileEntity finalizeUpload(Long fileId, Long size, Long requesterId, boolean isAdmin) {
        FileEntity entity = getFileById(fileId);

        if (!isAdmin && !java.util.Objects.equals(entity.getOwnerId(), requesterId)) {
            throw new AccessDeniedException("Unauthorized: You do not own this file session.");
        }

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
        String storedType = blob.getContentType() != null ? blob.getContentType() : entity.getContentType();
        byte[] head = storedHead(BlobId.of(props.getBucketName(), entity.getFileName()));
        ContentTypePolicy.Decision decision = ContentTypePolicy.decide(storedType, head == null ? new byte[0] : head);
        if (decision.attachment()) {
            storage.update(blobInfo(entity.getFileName(), decision));
        }
        entity.setContentType(decision.contentType());
        entity.setStatus("COMPLETED");
        return fileRepository.save(entity);
    }


    public FileEntity uploadFile(MultipartFile file, String directory, Long ownerId) {
        if (file.isEmpty()) throw new IllegalArgumentException("File is empty");

        try {
            String objectKey = sanitizeDirectory(directory) + "/" + System.currentTimeMillis() + "_"
                    + sanitizeFileName(file.getOriginalFilename());
            ContentTypePolicy.Decision decision;
            try (BufferedInputStream in = sniffable(file.getInputStream())) {
                decision = ContentTypePolicy.decide(file.getContentType(), head(in));
                storage.createFrom(blobInfo(objectKey, decision), in);
            }

            FileEntity fileEntity = new FileEntity();
            fileEntity.setFileName(objectKey);
            fileEntity.setOriginalFileName(file.getOriginalFilename());
            fileEntity.setContentType(decision.contentType());
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

        try {
            String objectKey = sanitizeDirectory(directory) + "/" + System.currentTimeMillis() + "_"
                    + sanitizeFileName(fileName);
            BufferedInputStream in = sniffable(inputStream);
            ContentTypePolicy.Decision decision = ContentTypePolicy.decide(contentType, head(in));
            String mimeType = decision.contentType();

            Blob blob = storage.createFrom(blobInfo(objectKey, decision), in);

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


    public FileEntity getFileById(Long fileId) {
        return fileRepository.findById(fileId)
                .orElseThrow(() -> new GCPFileException("File not found with ID: " + fileId));
    }

    @Transactional
    public void deleteFile(Long fileId, Long requestUserId, boolean isAdmin) {
        FileEntity fileEntity = getFileById(fileId);

        if (!isAdmin && !java.util.Objects.equals(fileEntity.getOwnerId(), requestUserId)) {
            LOGGER.error("Unauthorized delete attempt! User {} tried to delete file {} owned by {}",
                    requestUserId, fileId, fileEntity.getOwnerId());
            throw new AccessDeniedException("You do not have permission to delete this file.");
        }

        BlobId blobId = BlobId.of(props.getBucketName(), fileEntity.getFileName());
        String objectName = fileEntity.getFileName();

        fileRepository.delete(fileEntity);

        runAfterCommit(() -> deleteObject(blobId, objectName));
        LOGGER.info("Database record queued for deletion, file ID: {}", fileId);
    }

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
            LOGGER.error("Orphaned object: failed to delete {} from GCS after removing its row: {}",
                    objectName, e.getMessage());
        }
    }

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
        }
        String configured = props.getUpload().getBrowserOrigin();
        return (configured == null || configured.isBlank()) ? null : configured;
    }

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