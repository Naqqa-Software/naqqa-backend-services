package com.naqqa.seofarm.repository;

import com.naqqa.seofarm.entity.SeoSiteStateEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SeoSiteStateRepository extends MongoRepository<SeoSiteStateEntity, String> {
}
