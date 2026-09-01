package com.naqqa.auth.entity.auth;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.entity.authorities.SubRoleEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class UserEntity {

    /**
     * Placeholder stored in {@link #password} for accounts that have no local password —
     * today, those created by a social sign-in ({@code DefaultSocialAuthService}).
     *
     * <p>The column is {@code NOT NULL} and schemas are evolved with {@code ddl-auto=update},
     * which will not drop that constraint on existing databases, so "no password" is spelled
     * as a sentinel rather than as {@code null}. The value is deliberately not a valid BCrypt
     * hash, so {@code PasswordEncoder.matches} can never accept any input against it — but
     * callers should test {@link #hasUsablePassword()} instead of relying on that, both to say
     * what they mean and to avoid BCrypt's "does not look like BCrypt" warning on every try.
     */
    public static final String NO_PASSWORD = "{NO_PASSWORD}";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(unique = true, nullable = false)
    @EqualsAndHashCode.Include
    private String email;

    @Column(nullable = false)
    private String password;

    private String fullName;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    @Builder.Default
    private Set<RoleEntity> roles = new HashSet<>();

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "user_sub_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "sub_role_id")
    )
    @Builder.Default
    private Set<SubRoleEntity> subRoles = new HashSet<>();

    @JsonFormat(pattern = "dd-MM-yyyy")
    @JoinColumn(name = "birth_date")
    private LocalDate birthDate;

    @Builder.Default
    private boolean blocked = false;

    @Builder.Default
    private boolean enabled = true;

    private String googleId;
    private String facebookId;
    private String appleId;

    /**
     * Whether this account can be signed into with a password at all. False for a social-only
     * account until it sets one (password reset, or an in-session "set password" screen).
     *
     * <p>Intentionally not named {@code isX}/{@code getX}: Jackson must not pick this up as a
     * serialized property.
     */
    public boolean hasUsablePassword() {
        return password != null && !NO_PASSWORD.equals(password);
    }
}
