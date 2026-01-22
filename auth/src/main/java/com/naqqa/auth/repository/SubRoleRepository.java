package com.naqqa.auth.repository;

import com.naqqa.auth.entity.authorities.SubRoleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SubRoleRepository extends JpaRepository<SubRoleEntity, Long> {
    Optional<SubRoleEntity> findByName(String name);
}
