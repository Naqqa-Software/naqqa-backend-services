package com.naqqa.outreach.repository;

import com.naqqa.outreach.entity.LeadEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface LeadRepository extends MongoRepository<LeadEntity, String> {
}
