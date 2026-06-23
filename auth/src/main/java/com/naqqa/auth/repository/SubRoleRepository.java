package com.naqqa.auth.repository;

import com.naqqa.auth.entity.authorities.SubRoleEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface SubRoleRepository extends MongoRepository<SubRoleEntity, Long> {
    Optional<SubRoleEntity> findByName(String name);
}
