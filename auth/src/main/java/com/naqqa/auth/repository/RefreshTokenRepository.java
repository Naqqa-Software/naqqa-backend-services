package com.naqqa.auth.repository;

import com.naqqa.auth.entity.auth.RefreshTokenEntity;
import com.naqqa.auth.entity.auth.UserEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, Long> {
    Optional<RefreshTokenEntity> findByToken(String token);

    /**
     * Same as {@link #findByToken} but takes a row lock (SELECT ... FOR UPDATE), so two refreshes
     * presenting the same token are serialised: the second sees the first one's rotation.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshTokenEntity t where t.token = :token")
    Optional<RefreshTokenEntity> findByTokenForUpdate(@Param("token") String token);

    void deleteByToken(String token);

    /** Revokes a whole rotation family (logout, reuse detection). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RefreshTokenEntity t where t.familyId = :familyId")
    int deleteByFamilyId(@Param("familyId") String familyId);

    void deleteByUserAndDeviceId(UserEntity user, String deviceId);

    List<RefreshTokenEntity> findByExpiryDateBefore(Instant now);
}
