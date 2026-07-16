package com.naqqa.outreach.repository;

import com.naqqa.outreach.entity.OutreachSettingsEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface OutreachSettingsRepository extends MongoRepository<OutreachSettingsEntity, String> {
}
