package com.naqqa.auth.entity.auth;

import com.naqqa.auth.entity.authorities.RoleEntity;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "user_devices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDeviceEntity {

    @Id
    private Long id;

    private Long userId;

    private String deviceId;

    private String clientType;

    @DBRef
    private RoleEntity lastRole;

    private Instant createdAt;
}
