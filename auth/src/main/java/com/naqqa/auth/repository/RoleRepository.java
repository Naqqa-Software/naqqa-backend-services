package com.naqqa.auth.repository;

import com.naqqa.auth.entity.authorities.RoleEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface RoleRepository extends MongoRepository<RoleEntity, Long> {
    Optional<RoleEntity> findByName(String name);
}
