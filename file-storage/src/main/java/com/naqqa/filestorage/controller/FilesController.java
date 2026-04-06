package com.naqqa.filestorage.controller;


import com.naqqa.filestorage.entities.FileEntity;
import com.naqqa.filestorage.enums.FileAccessEnum;
import com.naqqa.filestorage.model.FinalizeUploadRequest;
import com.naqqa.filestorage.model.InitiateUploadResponse;
import com.naqqa.filestorage.service.FileStorageService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Arrays;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FilesController {

    private final FileStorageService fileStorageService;

    // ------------------ STANDARD UPLOADS (Small Assets) ------------------

    @PostMapping("/upload")
    public ResponseEntity<FileEntity> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "dir", defaultValue = "general") String directory,
            Authentication authentication) {

        Long ownerId = Long.valueOf(authentication.getName());
        FileEntity savedFile = fileStorageService.uploadFile(file, directory, ownerId);
        return new ResponseEntity<>(savedFile, HttpStatus.CREATED);
    }

    @PostMapping("/upload-multiple")
    public ResponseEntity<List<FileEntity>> uploadMultiple(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "dir", defaultValue = "general") String directory,
            Authentication authentication) {

        Long ownerId = Long.valueOf(authentication.getName());
        List<FileEntity> uploadedFiles = Arrays.stream(files)
                .map(file -> fileStorageService.uploadFile(file, directory, ownerId))
                .collect(Collectors.toList());

        return new ResponseEntity<>(uploadedFiles, HttpStatus.CREATED);
    }

    // ------------------ RESUMABLE UPLOADS (Course Videos/Large Files) ------------------

    /**
     * Step 1: Request a signed URL to start a background upload to GCS.
     */
    @PostMapping("/sessions/initiate")
    public ResponseEntity<InitiateUploadResponse> initiateSession(
            @RequestParam String fileName,
            @RequestParam String contentType,
            @RequestParam(defaultValue = "courses/videos") String dir,
            Authentication authentication) {

        Long ownerId = Long.valueOf(authentication.getName());
        InitiateUploadResponse response = fileStorageService.startResumableSession(dir, fileName, contentType, ownerId);
        return ResponseEntity.ok(response);
    }

    /**
     * Step 2: Record the file in the DB after the frontend finishes the background upload.
     */
    @PostMapping("/sessions/complete")
    public ResponseEntity<FileEntity> completeSession(
            @RequestBody FinalizeUploadRequest request,
            @RequestHeader(value = "X-Is-Admin", defaultValue = "false") boolean isAdmin,
            Authentication authentication) {
        
        Long requesterId = Long.valueOf(authentication.getName());
        FileEntity entity = fileStorageService.finalizeUpload(request.getFileId(), request.getSize(), requesterId, isAdmin);
        return ResponseEntity.ok(entity);
    }

    // ------------------ RETRIEVAL & DELETION ------------------

    @GetMapping("/{id}/url")
    public ResponseEntity<String> getFileUrl(
            @PathVariable Long id,
            @RequestParam(value = "access", defaultValue = "PRIVATE") FileAccessEnum access,
            @RequestHeader(value = "X-Is-Admin", defaultValue = "false") boolean isAdmin,
            Authentication authentication) {

        Long requesterId = Long.valueOf(authentication.getName());
        FileEntity fileEntity = fileStorageService.getFileById(id);

        // Security check: Only owner or admin can get the URL
        if (!isAdmin && !fileEntity.getOwnerId().equals(requesterId)) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }

        String url = fileStorageService.getFileUrl(fileEntity, access);
        return ResponseEntity.ok(url);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFile(
            @PathVariable Long id,
            @RequestHeader(value = "X-Is-Admin", defaultValue = "false") boolean isAdmin,
            Authentication authentication) {
        Long ownerId = Long.valueOf(authentication.getName());

        fileStorageService.deleteFile(id, ownerId, isAdmin);
        return ResponseEntity.noContent().build();
    }
}