package com.naqqa.auth.repository.redis;

import com.naqqa.auth.entity.RegisterRecordEntity;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface RegisterRecordRepository extends CrudRepository<RegisterRecordEntity, String> {
    Optional<Object> findByEmail(String email);
}
