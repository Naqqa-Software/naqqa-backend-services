package com.naqqa.outreach.repository;

import com.naqqa.outreach.entity.OutreachLogEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface OutreachLogRepository extends MongoRepository<OutreachLogEntity, String> {
}
