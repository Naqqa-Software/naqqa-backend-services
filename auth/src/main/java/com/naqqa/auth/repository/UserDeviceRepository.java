package com.naqqa.auth.repository;

import com.naqqa.auth.entity.auth.UserDeviceEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface UserDeviceRepository extends MongoRepository<UserDeviceEntity, Long> {
    Optional<UserDeviceEntity> findByUserIdAndDeviceId(Long userId, String deviceId);
    
    long countByUserId(Long userId);
    
    List<UserDeviceEntity> findByUserIdOrderByCreatedAtAsc(Long userId);
    
    void deleteByUserIdAndDeviceId(Long userId, String deviceId);
}