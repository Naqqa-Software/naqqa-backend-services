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

        boolean hasAuthority = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ADMIN".equals(a.getAuthority()));
        if (hasAuthority) return true;

        try {
            JsonNode principalNode = objectMapper.valueToTree(authentication.getPrincipal());
            if (principalNode != null && principalNode.has("role")) {
                JsonNode roleNode = principalNode.get("role");
                if (roleNode.has("name") && "ADMIN".equalsIgnoreCase(roleNode.get("name").asText())) {
                    return true;
                }
            }

            JsonNode detailsNode = objectMapper.valueToTree(authentication.getDetails());
            if (detailsNode != null && detailsNode.has("role")) {
                JsonNode roleNode = detailsNode.get("role");
                if (roleNode.has("name") && "ADMIN".equalsIgnoreCase(roleNode.get("name").asText())) {
                    return true;
                }
            }
        } catch (Exception e) {
        }

        return false;
    }


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

    @PostMapping("/sessions/complete")
    public ResponseEntity<FileEntity> completeSession(
            @RequestBody FinalizeUploadRequest request,
            Authentication authentication) {
        boolean isAdmin = isAdmin(authentication);

        Long requesterId = requesterId(authentication);
        FileEntity entity = fileStorageService.finalizeUpload(request.getFileId(), request.getSize(), requesterId, isAdmin);
        return ResponseEntity.ok(entity);
    }


    @GetMapping("/{id}/url")
    public ResponseEntity<String> getFileUrl(
            @PathVariable Long id,
            @RequestParam(value = "access", defaultValue = "PRIVATE") FileAccessEnum access,
            Authentication authentication) {

        boolean isAdmin = isAdmin(authentication);

        Long requesterId = requesterId(authentication);
        FileEntity fileEntity = fileStorageService.getFileById(id);

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