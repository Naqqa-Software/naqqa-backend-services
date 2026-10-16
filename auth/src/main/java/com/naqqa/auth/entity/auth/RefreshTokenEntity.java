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

    /**
     * Rotation family: every token minted by refreshing descends from one login and shares its id.
     * Null for tokens created before rotation existed (or by callers that build the entity
     * themselves); such a token starts its own family on its first refresh.
     */
    @Column(name = "family_id", length = 64)
    private String familyId;

    /** When this token was exchanged for a successor; null while it is the family's current token. */
    @Column(name = "rotated_at")
    private Instant rotatedAt;

    /** Id of the token minted when this one was rotated (lets a request inside the grace window catch up). */
    @Column(name = "replaced_by_id")
    private Long replacedById;

    public boolean isRotated() {
        return rotatedAt != null;
    }
}
