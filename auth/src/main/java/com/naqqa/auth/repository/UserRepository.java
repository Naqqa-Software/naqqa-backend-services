package com.naqqa.auth.repository;

import com.naqqa.auth.entity.auth.UserEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface UserRepository extends MongoRepository<UserEntity, Long> {
    Optional<UserEntity> findByEmail(String email);
    Optional<UserEntity> findByGoogleId(String googleId);
    Optional<UserEntity> findByFacebookId(String facebookId);
    Optional<UserEntity> findByAppleId(String appleId);
    boolean existsByEmail(String email);
}
