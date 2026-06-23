package com.naqqa.auth.entity.authorities;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.HashSet;
import java.util.Set;

@Document(collection = "sub_roles")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SubRoleEntity {

    @Id
    @EqualsAndHashCode.Include
    private Long id;

    @Indexed(unique = true)
    @EqualsAndHashCode.Include
    private String name;

    @DBRef
    private Set<AuthorityEntity> authorities = new HashSet<>();
}
