package com.naqqa.entity.repository.mongo;

import com.naqqa.entity.entity.Entity;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface EntityRepository extends MongoRepository<Entity, String> {
}

