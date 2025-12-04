package com.naqqa.auth.repository.redis;

import com.naqqa.auth.entity.auth.PasswordResetEntity;
import org.springframework.data.repository.CrudRepository;

public interface PasswordResetRepository extends CrudRepository<PasswordResetEntity, String> {
    void deleteByEmail(String email);
}
