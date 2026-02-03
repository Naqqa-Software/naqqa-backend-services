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
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class FileStorageService {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileStorageService.class);

    private final FileRepository fileRepository;
    private final Storage storage;
    private final String bucketName;

    // ------------------ UPLOAD METHODS ------------------

    public FileEntity uploadFile(MultipartFile file, String directory, FileAccessEnum access) {
        if (file.isEmpty()) throw new IllegalArgumentException("File is empty");

        try {
            String fileName = directory + "/" + System.currentTimeMillis() + "_" + file.getOriginalFilename();
            BlobId blobId = BlobId.of(bucketName, fileName);

            BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                    .setContentType(file.getContentType())
                    .build();

            Storage.PredefinedAcl storageAccess = (access == FileAccessEnum.PRIVATE)
                    ? Storage.PredefinedAcl.PRIVATE
                    : Storage.PredefinedAcl.PUBLIC_READ;

            Blob blob = storage.create(blobInfo, file.getBytes(), Storage.BlobTargetOption.predefinedAcl(storageAccess));

            FileEntity fileEntity = new FileEntity();
            fileEntity.setFileName(blob.getName());
            fileEntity.setOriginalFileName(file.getOriginalFilename());
            fileEntity.setFileUrl(blob.getMediaLink());
            fileEntity.setContentType(file.getContentType());
            fileEntity.setSize(file.getSize());

            return fileRepository.save(fileEntity);

        } catch (Exception e) {
            LOGGER.error("Error uploading file to GCS", e);
            throw new GCPFileException("Failed to upload file to GCS");
        }
    }

    public FileEntity uploadFileInputStream(InputStream inputStream, String fileName, String directory, FileAccessEnum access) {
        if (inputStream == null) throw new IllegalArgumentException("InputStream is null");

        try {
            String fullPath = directory + "/" + fileName;
            BlobId blobId = BlobId.of(bucketName, fullPath);

            BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                    .setContentType("application/octet-stream")
                    .build();

            Storage.PredefinedAcl storageAccess = (access == FileAccessEnum.PRIVATE)
                    ? Storage.PredefinedAcl.PRIVATE
                    : Storage.PredefinedAcl.PUBLIC_READ;

            Blob blob = storage.createFrom(blobInfo, inputStream, Storage.BlobWriteOption.predefinedAcl(storageAccess));

            FileEntity fileEntity = new FileEntity();
            fileEntity.setFileName(blob.getName());
            fileEntity.setOriginalFileName(fileName);
            fileEntity.setFileUrl(blob.getMediaLink());
            fileEntity.setContentType("application/octet-stream");

            return fileRepository.save(fileEntity);

        } catch (Exception e) {
            LOGGER.error("Error uploading InputStream to GCS", e);
            throw new GCPFileException("Failed to upload file to GCS");
        }
    }

    // ------------------ DELETE ------------------

    public void deleteFile(Long fileId) {
        FileEntity fileEntity = fileRepository.findById(fileId)
                .orElseThrow(() -> new GCPFileException("File not found with ID: " + fileId));

        BlobId blobId = BlobId.of(bucketName, fileEntity.getFileName());
        boolean deleted = storage.delete(blobId);

        if (deleted) {
            fileRepository.deleteById(fileId);
            LOGGER.info("File deleted from GCS and DB: {}", fileEntity.getFileName());
        } else {
            throw new GCPFileException("Failed to delete file from GCS: " + fileEntity.getFileName());
        }
    }

    // ------------------ FETCH ------------------

    public FileEntity getFile(Long fileId) {
        return fileRepository.findById(fileId)
                .orElseThrow(() -> new GCPFileException("File not found with ID: " + fileId));
    }

    public String generateSignedUrl(String fileName, long durationMinutes) {
        try {
            BlobId blobId = BlobId.of(bucketName, fileName);
            return storage.signUrl(
                    BlobInfo.newBuilder(blobId).build(),
                    durationMinutes,
                    TimeUnit.MINUTES,
                    Storage.SignUrlOption.withV4Signature()
            ).toString();
        } catch (Exception e) {
            LOGGER.error("Failed to generate signed URL for {}", fileName, e);
            throw new GCPFileException("Failed to generate signed URL for file: " + fileName);
        }
    }

    public List<FileEntity> getFilesWithAccess(List<Long> fileIds, FileAccessEnum access) {
        List<FileEntity> files = fileRepository.findAllById(fileIds);
        return files.stream().map(file -> {
            String url = (access == FileAccessEnum.PRIVATE)
                    ? generateSignedUrl(file.getFileName(), 60)
                    : file.getFileUrl();
            file.setFileUrl(url);
            return file;
        }).collect(Collectors.toList());
    }
}
