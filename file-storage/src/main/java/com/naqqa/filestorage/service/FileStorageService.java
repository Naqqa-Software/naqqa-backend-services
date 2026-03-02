package com.naqqa.filestorage.service;

import com.google.cloud.storage.*;
import com.naqqa.filestorage.entities.FileEntity;
import com.naqqa.filestorage.enums.FileAccessEnum;
import com.naqqa.filestorage.exceptions.GCPFileException;
import com.naqqa.filestorage.repository.FileRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
public class FileStorageService {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileStorageService.class);

    private final FileRepository fileRepository;
    private final Storage storage;
    private final String bucketName;

    // ------------------ UPLOAD METHODS ------------------

    public FileEntity uploadFile(MultipartFile file, String directory) {
        if (file.isEmpty()) throw new IllegalArgumentException("File is empty");

        try {
            String objectKey = directory + "/" + System.currentTimeMillis() + "_" + file.getOriginalFilename();
            BlobId blobId = BlobId.of(bucketName, objectKey);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                    .setContentType(file.getContentType())
                    .build();

            // Create object without ACLs (UBLA-compatible)
            storage.create(blobInfo, file.getBytes());

            FileEntity fileEntity = new FileEntity();
            fileEntity.setFileName(objectKey);
            fileEntity.setOriginalFileName(file.getOriginalFilename());
            fileEntity.setContentType(file.getContentType());
            fileEntity.setSize(file.getSize());

            return fileRepository.save(fileEntity);

        } catch (Exception e) {
            LOGGER.error("Error uploading file to GCS", e);
            throw new GCPFileException("Failed to upload file to GCS: " + e.getMessage(), e);
        }
    }

    public FileEntity uploadFileInputStream(InputStream inputStream, String fileName, String contentType, String directory) {
        if (inputStream == null) throw new IllegalArgumentException("InputStream is null");

        String mimeType = (contentType == null || contentType.isBlank()) ? "application/octet-stream" : contentType;

        try {
            String objectKey = directory + "/" + System.currentTimeMillis() + "_" + fileName;
            BlobId blobId = BlobId.of(bucketName, objectKey);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType(mimeType).build();

            storage.createFrom(blobInfo, inputStream);

            FileEntity fileEntity = new FileEntity();
            fileEntity.setFileName(objectKey);
            fileEntity.setOriginalFileName(fileName);
            fileEntity.setContentType(mimeType);

            return fileRepository.save(fileEntity);
        } catch (Exception e) {
            LOGGER.error("Error uploading InputStream to GCS", e);
            throw new GCPFileException("Failed to upload stream to GCS: " + e.getMessage(), e);
        }
    }

    // ------------------ DELETE ------------------
    public void deleteFile(Long fileId) {
        FileEntity fileEntity = fileRepository.findById(fileId)
                .orElseThrow(() -> new GCPFileException("File not found with ID: " + fileId));

        BlobId blobId = BlobId.of(bucketName, fileEntity.getFileName());
        if (storage.delete(blobId)) {
            fileRepository.deleteById(fileId);
            LOGGER.info("File deleted from GCS and DB: {}", fileEntity.getFileName());
        } else {
            throw new GCPFileException("Failed to delete file from GCS: " + fileEntity.getFileName());
        }
    }

    public String getFileUrl(FileEntity file, FileAccessEnum access) {
        int expiryMinutes = (access == FileAccessEnum.PRIVATE) ? 60 : 60 * 24 * 7;
        return generateSignedUrl(file.getFileName(), expiryMinutes);
    }

    private String generateSignedUrl(String objectKey, int durationMinutes) {
        try {
            BlobId blobId = BlobId.of(bucketName, objectKey);
            return storage.signUrl(
                    BlobInfo.newBuilder(blobId).build(),
                    durationMinutes,
                    TimeUnit.MINUTES,
                    Storage.SignUrlOption.withV4Signature()
            ).toString();
        } catch (Exception e) {
            LOGGER.error("Failed to generate signed URL for {}", objectKey, e);
            throw new GCPFileException("Failed to generate signed URL for file: " + objectKey);
        }
    }
}
