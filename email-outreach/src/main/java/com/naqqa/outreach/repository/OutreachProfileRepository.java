package com.naqqa.outreach.repository;

import com.naqqa.outreach.entity.OutreachProfileEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface OutreachProfileRepository extends MongoRepository<OutreachProfileEntity, String> {
    Optional<OutreachProfileEntity> findByKey(String key);

    List<OutreachProfileEntity> findAllByEnabledTrue();
}
