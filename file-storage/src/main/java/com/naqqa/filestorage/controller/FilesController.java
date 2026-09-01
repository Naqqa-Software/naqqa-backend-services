package com.naqqa.filestorage.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.filestorage.entities.FileEntity;
import com.naqqa.filestorage.enums.FileAccessEnum;
import com.naqqa.filestorage.model.FinalizeUploadRequest;
import com.naqqa.filestorage.model.InitiateUploadResponse;
import com.naqqa.filestorage.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Objects;
import java.util.Arrays;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FilesController {

    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * The caller's user id.
     *
     * <p>{@code Long.valueOf(getName())} on its own turns a non-numeric principal — any application
     * whose subject is a username or UUID — into a NumberFormatException and a 500. Failing as
     * "unauthenticated" is both the truthful answer and the safe one.
     */
    private Long requesterId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new AccessDeniedException("Unauthenticated");
        }
        try {
            return Long.valueOf(authentication.getName());
        } catch (NumberFormatException e) {
            throw new AccessDeniedException("Principal is not a numeric user id");
        }
    }

    private boolean isAdmin(Authentication authentication) {
        if (authentication == null) return false;

        // 1. Check standard authorities just in case
        boolean hasAuthority = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ADMIN".equals(a.getAuthority()));
        if (hasAuthority) return true;

        // 2. Check the principal structure for { "role": { "name": "ADMIN" } }
        try {
            JsonNode principalNode = objectMapper.valueToTree(authentication.getPrincipal());
            if (principalNode != null && principalNode.has("role")) {
                JsonNode roleNode = principalNode.get("role");
                if (roleNode.has("name") && "ADMIN".equalsIgnoreCase(roleNode.get("name").asText())) {
                    return true;
                }
            }

            // Fallback: Check details just in case it's stored there
            JsonNode detailsNode = objectMapper.valueToTree(authentication.getDetails());
            if (detailsNode != null && detailsNode.has("role")) {
                JsonNode roleNode = detailsNode.get("role");
                if (roleNode.has("name") && "ADMIN".equalsIgnoreCase(roleNode.get("name").asText())) {
                    return true;
                }
            }
        } catch (Exception e) {
            // Ignore mapping exceptions and default to false
        }

        return false;
    }

    // ------------------ STANDARD UPLOADS (Small Assets) ------------------

    @PostMapping("/upload")
    public ResponseEntity<FileEntity> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "dir", defaultValue = "general") String directory,
            Authentication authentication) {

        Long ownerId = requesterId(authentication);
        FileEntity savedFile = fileStorageService.uploadFile(file, directory, ownerId);
        return new ResponseEntity<>(savedFile, HttpStatus.CREATED);
    }

    @PostMapping("/upload-multiple")
    public ResponseEntity<List<FileEntity>> uploadMultiple(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "dir", defaultValue = "general") String directory,
            Authentication authentication) {

        Long ownerId = requesterId(authentication);
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

        Long ownerId = requesterId(authentication);
        InitiateUploadResponse response = fileStorageService.startResumableSession(dir, fileName, contentType, ownerId);
        return ResponseEntity.ok(response);
    }

    /**
     * Step 2: Record the file in the DB after the frontend finishes the background upload.
     */
    @PostMapping("/sessions/complete")
    public ResponseEntity<FileEntity> completeSession(
            @RequestBody FinalizeUploadRequest request,
            Authentication authentication) {
        boolean isAdmin = isAdmin(authentication);

        Long requesterId = requesterId(authentication);
        FileEntity entity = fileStorageService.finalizeUpload(request.getFileId(), request.getSize(), requesterId, isAdmin);
        return ResponseEntity.ok(entity);
    }

    // ------------------ RETRIEVAL & DELETION ------------------

    @GetMapping("/{id}/url")
    public ResponseEntity<String> getFileUrl(
            @PathVariable Long id,
            @RequestParam(value = "access", defaultValue = "PRIVATE") FileAccessEnum access,
            Authentication authentication) {

        boolean isAdmin = isAdmin(authentication);

        Long requesterId = requesterId(authentication);
        FileEntity fileEntity = fileStorageService.getFileById(id);

        // Security check: Only owner or admin can get the URL
        if (!isAdmin && !Objects.equals(fileEntity.getOwnerId(), requesterId)) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }

        String url = fileStorageService.getFileUrl(fileEntity, access);
        return ResponseEntity.ok(url);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFile(
            @PathVariable Long id,
            Authentication authentication) {
        Long ownerId = requesterId(authentication);

        boolean isAdmin = isAdmin(authentication);

        fileStorageService.deleteFile(id, ownerId, isAdmin);
        return ResponseEntity.noContent().build();
    }
}