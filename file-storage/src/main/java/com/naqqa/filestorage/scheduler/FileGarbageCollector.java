package com.naqqa.filestorage.scheduler;

import com.naqqa.filestorage.entities.FileEntity;
import com.naqqa.filestorage.repository.FileRepository;
import com.naqqa.filestorage.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class FileGarbageCollector {

    private final FileRepository fileRepository;
    private final FileStorageService fileStorageService;

    @Scheduled(cron = "0 0 * * * *") // Runs every hour
    public void cleanupUnlinkedFiles() {
        // Find files older than 24 hours that were never linked to anything
        LocalDateTime threshold = LocalDateTime.now().minusHours(24);

        List<FileEntity> abandonedFiles = fileRepository
                .findByIsLinkedFalseAndCreatedAtBefore(threshold);

        for (FileEntity file : abandonedFiles) {
            try {
                // Re-use your existing delete logic to clean GCS and DB
                // We pass true for isAdmin to bypass ownership validation
                fileStorageService.deleteFile(file.getId(), file.getOwnerId(), true);
            } catch (Exception e) {
                // Log and continue with next file
                log.error("Failed to garbage collect abandoned file {}: {}", file.getId(), e.getMessage());
            }
        }
    }
}