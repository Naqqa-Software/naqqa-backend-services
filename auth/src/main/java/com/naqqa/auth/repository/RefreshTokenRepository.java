package com.naqqa.auth.repository;

import com.naqqa.auth.entity.auth.RefreshTokenEntity;
import com.naqqa.auth.entity.auth.UserEntity;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends MongoRepository<RefreshTokenEntity, Long> {
    Optional<RefreshTokenEntity> findByToken(String token);
    void deleteByToken(String token);
    
    void deleteByUserAndDeviceId(UserEntity user, String deviceId);
    
    List<RefreshTokenEntity> findByExpiryDateBefore(Instant now);
}
