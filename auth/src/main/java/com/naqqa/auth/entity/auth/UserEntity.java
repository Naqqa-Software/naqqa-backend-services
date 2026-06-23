package com.naqqa.auth.entity.auth;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.entity.authorities.SubRoleEntity;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

@Document(collection = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class UserEntity {

    @Id
    @EqualsAndHashCode.Include
    private Long id;

    @Indexed(unique = true)
    @EqualsAndHashCode.Include
    private String email;

    private String password;

    private String fullName;

    @DBRef
    @Builder.Default
    private Set<RoleEntity> roles = new HashSet<>();

    @DBRef
    @Builder.Default
    private Set<SubRoleEntity> subRoles = new HashSet<>();

    @JsonFormat(pattern = "dd-MM-yyyy")
    private LocalDate birthDate;

    @Builder.Default
    private boolean blocked = false;

    @Builder.Default
    private boolean enabled = true;

    private String googleId;
    private String facebookId;
    private String appleId;
}
