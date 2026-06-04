package com.naqqa.auth.entity.auth;

import com.naqqa.auth.entity.authorities.RoleEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "user_devices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDeviceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Column(name = "client_type")
    private String clientType;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "last_role_id", nullable = false)
    private RoleEntity lastRole;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}