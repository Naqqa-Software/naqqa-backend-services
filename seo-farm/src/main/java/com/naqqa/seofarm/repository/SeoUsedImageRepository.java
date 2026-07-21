package com.naqqa.seofarm.repository;

import com.naqqa.seofarm.entity.SeoUsedImageEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface SeoUsedImageRepository extends MongoRepository<SeoUsedImageEntity, String> {

    List<SeoUsedImageEntity> findBySiteId(String siteId);

    boolean existsBySiteIdAndImageId(String siteId, String imageId);
}
