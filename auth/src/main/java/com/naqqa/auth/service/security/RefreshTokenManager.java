package com.naqqa.auth.service.security;

import com.naqqa.auth.entity.auth.RefreshTokenEntity;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.repository.RefreshTokenRepository;
import com.naqqa.auth.sso.store.MongoRefreshStore;
import com.naqqa.sso.session.RefreshRecord;
import com.naqqa.sso.session.RefreshTokenService;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RefreshTokenManager {

    public static final String FAMILY_PREFIX = "fam:";
    public static final int TOKEN_LENGTH = 43;
    static final String LEGACY_DUMMY = "legacy-refresh-token-placeholder";

    public enum Outcome {
        OK, LEGACY, REUSED, EXPIRED, INVALID
    }

    public record Issued(String token, RefreshTokenEntity binding) {
    }

    public record Rotation(Outcome outcome, String token, RefreshTokenEntity binding) {
    }

    public record Binding(String deviceId, String clientId, String clientType, String sessionId, Collection<String> amr,
                          String acr, Instant authTime, boolean rememberMe) {
    }

    private final RefreshTokenService refreshTokenService;
    private final MongoRefreshStore refreshStore;
    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;

    static String hash(String token) {
        return Bytes.b64url(Hashes.sha256(Bytes.utf8(token)));
    }

    public Issued issue(UserEntity user, RoleEntity activeRole, Binding binding) {
        RefreshTokenService.Issued issued = refreshTokenService.issue(String.valueOf(user.getId()), "");
        RefreshTokenEntity entity = RefreshTokenEntity.builder()
                .token(FAMILY_PREFIX + issued.familyId())
                .user(user)
                .deviceId(binding.deviceId())
                .activeRole(activeRole)
                .expiryDate(issued.familyExpiry())
                .sessionId(binding.sessionId())
                .clientId(binding.clientId())
                .clientType(binding.clientType())
                .amr(binding.amr() == null ? List.of() : new ArrayList<>(binding.amr()))
                .acr(binding.acr())
                .authTime(binding.authTime())
                .rememberMe(binding.rememberMe())
                .build();
        refreshTokenRepository.save(entity);
        return new Issued(issued.token(), entity);
    }

    public Rotation rotate(String presented) {
        if (presented == null || presented.isEmpty() || presented.length() > 4096) {
            return new Rotation(Outcome.INVALID, null, null);
        }
        if (presented.length() == TOKEN_LENGTH) {
            RefreshTokenService.Rotation r = refreshTokenService.rotate(presented, "");
            switch (r.status()) {
                case OK -> {
                    RefreshTokenEntity binding = refreshTokenRepository.findByToken(FAMILY_PREFIX + r.familyId()).orElse(null);
                    if (binding == null || binding.getExpiryDate() == null || !clock.instant().isBefore(binding.getExpiryDate())) {
                        refreshTokenService.revokeFamily(r.familyId());
                        if (binding != null) {
                            refreshTokenRepository.delete(binding);
                        }
                        return new Rotation(Outcome.INVALID, null, null);
                    }
                    return new Rotation(Outcome.OK, r.token(), binding);
                }
                case REUSE_DETECTED -> {
                    RefreshTokenEntity binding = familyOf(presented)
                            .flatMap(f -> refreshTokenRepository.findByToken(FAMILY_PREFIX + f))
                            .orElse(null);
                    if (binding != null) {
                        refreshTokenRepository.delete(binding);
                    }
                    return new Rotation(Outcome.REUSED, null, binding);
                }
                case EXPIRED -> {
                    familyOf(presented).ifPresent(this::deleteBinding);
                    return new Rotation(Outcome.EXPIRED, null, null);
                }
                case REVOKED, CLIENT_MISMATCH -> {
                    return new Rotation(Outcome.INVALID, null, null);
                }
                default -> {
                }
            }
        }
        if (presented.startsWith(FAMILY_PREFIX)) {
            return new Rotation(Outcome.INVALID, null, null);
        }
        RefreshTokenEntity legacy = refreshTokenRepository.removeByToken(presented);
        String stored = legacy == null ? LEGACY_DUMMY : legacy.getToken();
        boolean matches = Bytes.constantTimeEquals(hash(stored), hash(presented));
        if (legacy == null || !matches) {
            return new Rotation(Outcome.INVALID, null, null);
        }
        if (legacy.getExpiryDate() == null || !clock.instant().isBefore(legacy.getExpiryDate())) {
            return new Rotation(Outcome.EXPIRED, null, null);
        }
        return new Rotation(Outcome.LEGACY, null, legacy);
    }

    public Optional<String> familyOf(String presented) {
        if (presented == null || presented.length() != TOKEN_LENGTH) {
            return Optional.empty();
        }
        return refreshStore.find(hash(presented)).map(RefreshRecord::familyId);
    }

    public static String familyIdOf(RefreshTokenEntity binding) {
        String token = binding == null ? null : binding.getToken();
        return token != null && token.startsWith(FAMILY_PREFIX) ? token.substring(FAMILY_PREFIX.length()) : null;
    }

    public Optional<RefreshTokenEntity> bindingOf(String presented) {
        if (presented == null) {
            return Optional.empty();
        }
        if (presented.length() == TOKEN_LENGTH) {
            return familyOf(presented).flatMap(f -> refreshTokenRepository.findByToken(FAMILY_PREFIX + f));
        }
        if (presented.startsWith(FAMILY_PREFIX)) {
            return Optional.empty();
        }
        return refreshTokenRepository.findByToken(presented);
    }

    private void deleteBinding(String familyId) {
        refreshTokenRepository.findByToken(FAMILY_PREFIX + familyId).ifPresent(refreshTokenRepository::delete);
    }

    public Optional<RefreshTokenEntity> revoke(String presented) {
        Optional<RefreshTokenEntity> binding = bindingOf(presented);
        if (presented != null && presented.length() == TOKEN_LENGTH) {
            refreshTokenService.revoke(presented);
        }
        binding.ifPresent(refreshTokenRepository::delete);
        return binding;
    }

    public void revokeBinding(RefreshTokenEntity binding) {
        String family = familyIdOf(binding);
        if (family != null) {
            refreshTokenService.revokeFamily(family);
        }
        if (binding.getId() != null) {
            refreshTokenRepository.delete(binding);
        }
    }

    public void revokeSession(String sessionId) {
        if (sessionId == null) {
            return;
        }
        for (RefreshTokenEntity binding : refreshTokenRepository.findBySessionId(sessionId)) {
            revokeBinding(binding);
        }
    }

    public List<RefreshTokenEntity> bindingsForDevice(UserEntity user, String deviceId) {
        if (user == null || deviceId == null) {
            return List.of();
        }
        return refreshTokenRepository.findByUser(user).stream().filter(b -> deviceId.equals(b.getDeviceId())).toList();
    }

    public void revokeAllForUser(UserEntity user) {
        revokeAllExcept(user, null);
    }

    public void revokeAllExcept(UserEntity user, String keepSessionId) {
        if (keepSessionId == null) {
            refreshTokenService.revokeAllForUser(String.valueOf(user.getId()));
        }
        for (RefreshTokenEntity binding : refreshTokenRepository.findByUser(user)) {
            if (keepSessionId == null || !keepSessionId.equals(binding.getSessionId())) {
                revokeBinding(binding);
            }
        }
    }

    public void updateActiveRole(String presented, RoleEntity role) {
        bindingOf(presented).ifPresent(b -> {
            b.setActiveRole(role);
            refreshTokenRepository.save(b);
        });
    }
}
