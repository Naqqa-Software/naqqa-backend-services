package com.naqqa.entity.repository;

import com.naqqa.deedakt.entities.profile.UserProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserProfileRepository extends JpaRepository<UserProfileEntity, Long> {

    /**
     * Finds the profile associated with a specific user ID.
     * Useful because we identify the user via Authentication/Email first.
     */
    Optional<UserProfileEntity> findByUserId(Long userId);
}