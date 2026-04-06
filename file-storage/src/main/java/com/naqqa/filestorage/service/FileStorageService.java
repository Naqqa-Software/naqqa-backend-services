package com.naqqa.filestorage.service;

import com.google.cloud.storage.*;
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

@Service
@RequiredArgsConstructor
@Transactional // Ensures database operations roll back if something fails mid-process
public class FileStorageService {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileStorageService.class);

    private final FileRepository fileRepository;
    private final Storage storage;
    private final com.naqqa.filestorage.config.FileStorageProperties props;

    // ------------------ RESUMABLE UPLOAD (For Background/Large Files) ------------------
    public InitiateUploadResponse startResumableSession(String directory, String fileName, String contentType, Long ownerId) {
        String objectKey = directory + "/" + System.currentTimeMillis() + "_" + fileName;

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

    // Helper for Step 1
    private String initiateResumableUploadByKey(String objectKey, String contentType) {
        BlobId blobId = BlobId.of(props.getBucketName(), objectKey);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType(contentType).build();

        URL signedUrl = storage.signUrl(blobInfo, 1, TimeUnit.HOURS,
                Storage.SignUrlOption.withV4Signature(),
                Storage.SignUrlOption.httpMethod(HttpMethod.PUT)
        );
        return signedUrl.toString();
    }


    /**
     * Step 2: Finalizes the record in the database once the frontend confirms GCS upload is done.
     */
    public FileEntity finalizeUpload(Long fileId, Long size, Long requesterId, boolean isAdmin) {
        FileEntity entity = getFileById(fileId);
        
        if (!isAdmin && !entity.getOwnerId().equals(requesterId)) {
            throw new AccessDeniedException("Unauthorized: You do not own this file session.");
        }

        entity.setSize(size);
        entity.setStatus("COMPLETED");
        return fileRepository.save(entity);
    }

    // ------------------ STANDARD UPLOAD METHODS ------------------

    public FileEntity uploadFile(MultipartFile file, String directory, Long ownerId) {
        if (file.isEmpty()) throw new IllegalArgumentException("File is empty");

        try {
            String objectKey = directory + "/" + System.currentTimeMillis() + "_" + file.getOriginalFilename();
            BlobId blobId = BlobId.of(props.getBucketName(), objectKey);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                    .setContentType(file.getContentType())
                    .build();

            storage.create(blobInfo, file.getBytes());

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
            String objectKey = directory + "/" + System.currentTimeMillis() + "_" + fileName;
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

    public void deleteFile(Long fileId, Long requestUserId) {
        FileEntity fileEntity = getFileById(fileId);

        if (!fileEntity.getOwnerId().equals(requestUserId)) {
            LOGGER.error("Unauthorized delete attempt! User {} tried to delete file {} owned by {}",
                    requestUserId, fileId, fileEntity.getOwnerId());
            throw new AccessDeniedException("You do not have permission to delete this file.");
        }

        BlobId blobId = BlobId.of(props.getBucketName(), fileEntity.getFileName());

        boolean deletedFromGcs = storage.delete(blobId);

        if (!deletedFromGcs) {
            LOGGER.warn("File {} was not found in GCS. Proceeding to delete database record anyway.",
                    fileEntity.getFileName());
        } else {
            LOGGER.info("File {} successfully deleted from GCS.", fileEntity.getFileName());
        }

        fileRepository.delete(fileEntity);
        LOGGER.info("Database record deleted for file ID: {}", fileId);
    }

    public String getFileUrl(FileEntity file, FileAccessEnum access) {
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

    public void markFilesAsLinked(List<Long> fileIds) {
        List<FileEntity> files = fileRepository.findAllById(fileIds);
        files.forEach(f -> f.setLinked(true));
        fileRepository.saveAll(files);
    }
}