package com.naqqa.auth.repository;

import com.naqqa.auth.entity.authorities.AuthorityEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Set;

public interface AuthorityRepository  extends JpaRepository<AuthorityEntity, Long> {
    List<AuthorityEntity> findAllByNameIn(Set<String> authorityNames);
}
