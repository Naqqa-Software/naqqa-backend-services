package com.naqqa.auth.repository;

import com.naqqa.auth.entity.auth.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByEmail(String email);
    Optional<UserEntity> findByGoogleId(String googleId);
    Optional<UserEntity> findByFacebookId(String facebookId);
    Optional<UserEntity> findByAppleId(String appleId);
    boolean existsByEmail(String email);
}
