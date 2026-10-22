package com.naqqa.auth.entity.auth;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "refresh_tokens", indexes = @Index(name = "idx_refresh_tokens_family", columnList = "family_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String token;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(nullable = false)
    private String deviceId;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "active_role_id", nullable = false)
    private com.naqqa.auth.entity.authorities.RoleEntity activeRole;

    @Column(nullable = false)
    private Instant expiryDate;

    @Column(name = "family_id", length = 64)
    private String familyId;

    @Column(name = "rotated_at")
    private Instant rotatedAt;

    @Column(name = "replaced_by_id")
    private Long replacedById;

    public boolean isRotated() {
        return rotatedAt != null;
    }
}
