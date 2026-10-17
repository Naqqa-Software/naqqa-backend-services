package com.naqqa.auth.entity.auth;

import com.naqqa.auth.entity.authorities.RoleEntity;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "refresh_tokens")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshTokenEntity {

    @Id
    private Long id;

    @Indexed(unique = true)
    private String token;

    @DBRef(lazy = true)
    private UserEntity user;

    private String deviceId;

    @DBRef
    private RoleEntity activeRole;

    private Instant expiryDate;

    private String sessionId;

    private String clientId;

    private String clientType;

    private List<String> amr;

    private String acr;

    private Instant authTime;

    private boolean rememberMe;
}
