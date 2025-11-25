package com.naqqa.auth.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;

@Data
@NoArgsConstructor
@RedisHash(value = "RegisterRecord", timeToLive = 21600L)
public class RegisterRecordEntity {

    @Id
    private String uuid;

    private String fullName;
    private String email;
    private String password;
    private String code;
}

