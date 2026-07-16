package com.naqqa.outreach.repository;

import com.naqqa.outreach.entity.OutreachAccountStateEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface OutreachAccountStateRepository extends MongoRepository<OutreachAccountStateEntity, String> {
    Optional<OutreachAccountStateEntity> findByProfileKey(String profileKey);
}
