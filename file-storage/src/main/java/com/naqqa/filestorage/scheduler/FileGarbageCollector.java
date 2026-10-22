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

    @Value("${gcs.lib.gc-batch-size:500}")
    private int batchSize;

    @Value("${gcs.lib.gc-grace-hours:24}")
    private int graceHours;

    @Scheduled(cron = "0 0 * * * *")
    public void cleanupUnlinkedFiles() {
        LocalDateTime threshold = LocalDateTime.now().minusHours(graceHours);

        List<FileEntity> abandonedFiles = fileRepository
                .findByIsLinkedFalseAndCreatedAtBefore(threshold, PageRequest.of(0, batchSize));
        if (abandonedFiles.isEmpty()) {
            return;
        }
        log.info("Garbage collecting {} abandoned file(s) older than {}", abandonedFiles.size(), threshold);

        for (FileEntity file : abandonedFiles) {
            try {
                fileStorageService.deleteFile(file.getId(), file.getOwnerId(), true);
            } catch (Exception e) {
                log.error("Failed to garbage collect abandoned file {}: {}", file.getId(), e.getMessage());
            }
        }
    }
}