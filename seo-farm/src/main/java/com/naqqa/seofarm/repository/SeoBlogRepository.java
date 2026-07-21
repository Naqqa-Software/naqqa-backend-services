package com.naqqa.seofarm.repository;

import com.naqqa.seofarm.entity.SeoBlogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SeoBlogRepository extends MongoRepository<SeoBlogEntity, String> {

    Page<SeoBlogEntity> findByFromSite(String fromSite, Pageable pageable);

    Optional<SeoBlogEntity> findBySlug(String slug);

    boolean existsBySlug(String slug);

    long countByFromSite(String fromSite);

    /** Does a blog for this site actually exist since {@code from} (i.e. today)? Deleting it makes this false. */
    boolean existsByFromSiteAndCreatedAtGreaterThanEqual(String fromSite, Instant from);

    List<SeoBlogEntity> findByFromSiteOrderByCreatedAtDesc(String fromSite);
}
