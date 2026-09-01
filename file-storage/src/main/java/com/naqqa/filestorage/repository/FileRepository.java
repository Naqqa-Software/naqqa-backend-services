package com.naqqa.filestorage.repository;

import com.naqqa.filestorage.entities.FileEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface FileRepository extends JpaRepository<FileEntity, Long> {
    Optional<FileEntity> findByFileName(String fileName);

    List<FileEntity> findByIsLinkedFalseAndCreatedAtBefore(LocalDateTime threshold);

    /** Bounded variant used by the garbage collector; see {@code gcs.lib.gc-batch-size}. */
    List<FileEntity> findByIsLinkedFalseAndCreatedAtBefore(LocalDateTime threshold, Pageable pageable);
}
