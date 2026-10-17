package com.naqqa.auth.entity.auth;

import lombok.Builder;
import lombok.Getter;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;

import java.time.Instant;

@Builder
@Getter
@RedisHash(value = "PasswordReset", timeToLive = 1800L)
public class PasswordResetEntity {
    @Id
    private String email;
    private String uuid;
    private Instant expiresAt;
}
