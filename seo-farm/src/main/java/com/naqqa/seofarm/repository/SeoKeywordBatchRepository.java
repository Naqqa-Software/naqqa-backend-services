package com.naqqa.seofarm.repository;

import com.naqqa.seofarm.entity.SeoKeywordBatchEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface SeoKeywordBatchRepository extends MongoRepository<SeoKeywordBatchEntity, String> {

    List<SeoKeywordBatchEntity> findBySiteIdOrderByExtractedAtDesc(String siteId);
}
