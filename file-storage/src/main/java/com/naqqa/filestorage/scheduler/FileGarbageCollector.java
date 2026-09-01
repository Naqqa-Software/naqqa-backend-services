package com.naqqa.filestorage.scheduler;

import com.naqqa.filestorage.entities.FileEntity;
import com.naqqa.filestorage.repository.FileRepository;
import com.naqqa.filestorage.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
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

    /**
     * Bound on one pass. Unbounded, a large backlog means loading every abandoned row into memory
     * and a scheduled task that runs for hours; the next hourly pass picks up the remainder.
     */
    @Value("${gcs.lib.gc-batch-size:500}")
    private int batchSize;

    /** Grace window before an unlinked upload is considered abandoned. */
    @Value("${gcs.lib.gc-grace-hours:24}")
    private int graceHours;

    @Scheduled(cron = "0 0 * * * *") // Runs every hour
    public void cleanupUnlinkedFiles() {
        // Find files older than 24 hours that were never linked to anything
        LocalDateTime threshold = LocalDateTime.now().minusHours(graceHours);

        List<FileEntity> abandonedFiles = fileRepository
                .findByIsLinkedFalseAndCreatedAtBefore(threshold, PageRequest.of(0, batchSize));
        if (abandonedFiles.isEmpty()) {
            return;
        }
        log.info("Garbage collecting {} abandoned file(s) older than {}", abandonedFiles.size(), threshold);

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