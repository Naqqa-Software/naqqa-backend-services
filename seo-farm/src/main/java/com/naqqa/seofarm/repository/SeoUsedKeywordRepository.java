package com.naqqa.seofarm.repository;

import com.naqqa.seofarm.entity.SeoUsedKeywordEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface SeoUsedKeywordRepository extends MongoRepository<SeoUsedKeywordEntity, String> {

    List<SeoUsedKeywordEntity> findBySiteId(String siteId);

    boolean existsBySiteIdAndKeyword(String siteId, String keyword);
}
