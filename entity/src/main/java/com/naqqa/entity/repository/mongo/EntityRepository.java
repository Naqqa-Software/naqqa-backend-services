package com.naqqa.entity.repository.mongo;

import com.naqqa.entity.entity.Entity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface EntityRepository extends MongoRepository<Entity, String> {
    Optional<Entity> findByMainDetailsKey(String key);
    boolean existsByMainDetailsKey(String key);
}
