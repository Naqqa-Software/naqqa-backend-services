package com.naqqa.seofarm.repository;

import com.naqqa.seofarm.entity.SeoGenerationLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.Collection;

public interface SeoGenerationLogRepository extends MongoRepository<SeoGenerationLogEntity, String> {

    Page<SeoGenerationLogEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Recent logs limited to the given (active) sites. */
    Page<SeoGenerationLogEntity> findBySiteIdInOrderByCreatedAtDesc(Collection<String> siteIds, Pageable pageable);

    long countByStatus(String status);

    /** Has this site already produced a blog with the given status since {@code from} (i.e. today)? */
    boolean existsBySiteIdAndStatusAndCreatedAtGreaterThanEqual(String siteId, String status, Instant from);
}
