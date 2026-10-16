package com.naqqa.auth.service.auth;

import com.naqqa.auth.config.auth.EmailMessages;
import com.naqqa.auth.config.auth.Errors;
import com.naqqa.auth.dto.auth.*;
import com.naqqa.auth.entity.auth.*;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.exceptions.BadRequestException;
import com.naqqa.auth.exceptions.InternalServerErrorException;
import com.naqqa.auth.exceptions.auth.*;
import com.naqqa.auth.repository.*;
import com.naqqa.auth.repository.redis.PasswordResetRepository;
import com.naqqa.auth.repository.redis.RegisterRecordRepository;
import com.naqqa.auth.security.CookieUtils;
import com.naqqa.auth.security.JwtService;
import com.naqqa.auth.roles.RoleProvider;
import com.naqqa.auth.service.security.CodeGenService;
import com.naqqa.auth.service.security.DeviceSessionService;
import com.naqqa.auth.service.security.TokenService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnMissingBean(AuthService.class)
public class DefaultAuthService implements AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserDeviceRepository userDeviceRepository;
    private final DeviceSessionService deviceSessionService;
    private final PasswordEncoder passwordEncoder;
    private final RegisterRecordRepository registerRecordRepository;
    private final PasswordResetRepository passwordResetRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final RoleProvider roleProvider;
    private final TokenService tokenService;
    private final CodeGenService codeGenService;
    private final AuthEmailService authEmailService;
    private final EmailMessages emailMessages;

    /** Seconds during which a just-rotated refresh token may be presented again (parallel tabs/requests). */
    @Value("${jwt.refresh-reuse-grace-seconds:20}")
    private long refreshReuseGraceSeconds = 20;

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new EmailInUseException();
        }

        RegisterRecordEntity reg = new RegisterRecordEntity();
        reg.setUuid(UUID.randomUUID().toString());
        reg.setFullName(request.getFullName());
        reg.setEmail(request.getEmail());
        reg.setPassword(passwordEncoder.encode(request.getPassword()));
        reg.setCode(codeGenService.generateRandomString());

        registerRecordRepository.save(reg);

        authEmailService.sendEmail(
                request.getEmail(),
                emailMessages.getSubjectEmailConfirmation(),
                emailMessages.getEmailAddressConfirmationMessage(reg.getUuid(), reg.getCode())
        );

        RegisterResponse response = new RegisterResponse();
        response.setUuid(reg.getUuid());

        return response;
    }


    @Transactional
    public UserEntity confirmEmail(EmailConfirmationRequest request) {

        RoleEntity userRole = roleRepository.findByName(roleProvider.getDefaultRole())
                .orElseThrow(() -> new InternalServerErrorException(Errors.INTERNAL_ERROR));

        RegisterRecordEntity reg = registerRecordRepository
                .findById(request.getUuid())
                .orElseThrow(() -> new BadRequestException(Errors.REG_PENDING_NOT_FOUND));

        if (!reg.getCode().equals(request.getCode())) {
            throw new BadRequestException(Errors.REG_CODE_INVALID);
        }

        UserEntity user = UserEntity.builder()
                .fullName(reg.getFullName())
                .email(reg.getEmail())
                .password(reg.getPassword())
                .blocked(false)
                .roles(Collections.singleton(userRole))
                .build();

        userRepository.save(user);
        registerRecordRepository.deleteById(reg.getUuid());

        return user;
    }


    @Transactional
    public AuthResponse login(LoginRequest request, boolean rememberMe, HttpServletResponse response) {
        UserEntity user = userRepository.findByEmail(request.getEmail()).orElse(null);

        if (user == null) {
            RegisterRecordEntity reg = registerRecordRepository.findByEmail(request.getEmail())
                    .orElse(null);

            if (reg != null) {
                String uuid = resendVerificationCode(request.getEmail());
                throw new EmailNotVerifiedException(uuid);
            }

            throw new WrongCredentialsException();
        }

        // A social-only account has no password to check; fail as plain wrong credentials so the
        // response gives away nothing about which providers the address is registered with.
        if (!user.hasUsablePassword()
                || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new WrongCredentialsException();
        }

        if (!user.isEnabled()) {
            throw new AccountBlockedException();
        }

        String deviceId = request.getDeviceId() != null && !request.getDeviceId().isBlank() ? request.getDeviceId() : "DEFAULT_DEVICE";
        String clientType = request.getClientType() != null && !request.getClientType().isBlank() ? request.getClientType() : "DEFAULT";

        log.info("Device ID: {}, Client Type: {} for user: {}", deviceId, clientType, user.getEmail());

        RoleEntity activeRole = null;

        UserDeviceEntity userDevice = userDeviceRepository.findByUserIdAndDeviceId(user.getId(), deviceId)
                .orElse(null);

        if (userDevice != null) {
            RoleEntity lastRole = userDevice.getLastRole();

            if (lastRole != null) {
                boolean allowed = roleProvider.isRoleAllowed(clientType, lastRole.getName());
                boolean hasRole = user.getRoles().contains(lastRole);

                if (allowed && hasRole) {
                    activeRole = lastRole;
                    log.info("Restoring last active role: {}", activeRole.getName());
                }
            }
        } else {
            log.info("No user device record found for deviceId: {}", deviceId);
        }

        if (activeRole == null) {
            String fallbackRoleName = roleProvider.getFallbackRole(user, clientType);

            activeRole = user.getRoles().stream()
                    .filter(r -> r.getName().equals(fallbackRoleName))
                    .findFirst()
                    .orElse(null);

            if (activeRole == null || !roleProvider.isRoleAllowed(clientType, activeRole.getName())) {
                activeRole = user.getRoles().stream()
                        .filter(r -> roleProvider.isRoleAllowed(clientType, r.getName()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("No allowed roles found for user"));
            }
        }
        
        deviceSessionService.handleDeviceLogin(user, deviceId, clientType, activeRole);

        String accessToken = tokenService.generateAccessToken(user, activeRole);
        String refreshToken = tokenService.generateRefreshToken(user);

        RefreshTokenEntity refreshTokenEntity = RefreshTokenEntity.builder()
                .token(refreshToken)
                .user(user)
                .deviceId(deviceId)
                .activeRole(activeRole)
                .expiryDate(Instant.now().plus(30, ChronoUnit.DAYS))
                .familyId(UUID.randomUUID().toString())
                .build();

        refreshTokenRepository.save(refreshTokenEntity);
        
        CookieUtils.setCookie(response, CookieUtils.ACCESS_TOKEN_COOKIE, accessToken, 15 * 60);
        CookieUtils.setCookie(response, CookieUtils.REFRESH_TOKEN_COOKIE, refreshToken, 30 * 24 * 60 * 60);

        Set<String> authorities = java.util.stream.Stream.concat(
                activeRole.getAuthorities().stream().map(com.naqqa.auth.entity.authorities.AuthorityEntity::getName),
                user.getSubRoles().stream().flatMap(sr -> sr.getAuthorities().stream()).map(com.naqqa.auth.entity.authorities.AuthorityEntity::getName)
        ).collect(java.util.stream.Collectors.toSet());

        return AuthResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .roles(user.getRoles().stream().map(RoleEntity::getName).collect(java.util.stream.Collectors.toSet()))
                .role(AuthResponse.RoleSummary.builder()
                        .id(activeRole.getId())
                        .name(activeRole.getName())
                        .build())
                .authorities(authorities)
                .build();
    }

    /**
     * Refresh-token rotation: every successful refresh consumes the presented token and issues a new
     * one (new jti, same family, same absolute expiry, same device and active role). The consumed
     * token stays in the table, marked as rotated, so that presenting it again can be recognised:
     * <ul>
     *   <li>within {@code jwt.refresh-reuse-grace-seconds} of its rotation (parallel tabs or requests
     *       that sent the old cookie before the new one arrived) it answers with the family's current
     *       token and a fresh access token, instead of logging the user out;</li>
     *   <li>after that window it is treated as a stolen copy: the whole family is revoked and the
     *       call fails with {@link Errors#TOKEN_REUSED}.</li>
     * </ul>
     */
    @Transactional(noRollbackFor = BadRequestException.class)
    public String refresh(String refreshToken, HttpServletResponse response) {
        // Row lock: concurrent refreshes with the same token are serialised, so the second one sees
        // the first one's rotation and takes the grace path instead of minting a second successor.
        RefreshTokenEntity presented = refreshTokenRepository.findByTokenForUpdate(refreshToken)
                .orElseThrow(() -> new BadRequestException(Errors.TOKEN_INVALID));
        Instant now = Instant.now();

        if (presented.isRotated()) {
            boolean inGrace = !presented.getRotatedAt().plusSeconds(refreshReuseGraceSeconds).isBefore(now);
            RefreshTokenEntity current = inGrace ? currentOfFamily(presented, now) : null;
            if (current != null) {
                log.debug("Refresh token of family {} presented again within the grace window", presented.getFamilyId());
                return issueCookies(current, response, now);
            }
            refreshTokenRepository.deleteByFamilyId(presented.getFamilyId());
            CookieUtils.clearCookie(response, CookieUtils.ACCESS_TOKEN_COOKIE);
            CookieUtils.clearCookie(response, CookieUtils.REFRESH_TOKEN_COOKIE);
            if (inGrace) {
                // The family was already ended (logout / revocation) - nothing was stolen.
                throw new BadRequestException(Errors.TOKEN_INVALID);
            }
            log.warn("Refresh token reuse detected (family {}, user {}): family revoked",
                    presented.getFamilyId(), presented.getUser().getId());
            throw new BadRequestException(Errors.TOKEN_REUSED);
        }

        if (presented.getExpiryDate().isBefore(now)) {
            refreshTokenRepository.delete(presented);
            throw new BadRequestException(Errors.TOKEN_EXPIRED);
        }

        if (!jwtService.isTokenValid(refreshToken, String.valueOf(presented.getUser().getId()), true)) {
            throw new BadRequestException(Errors.TOKEN_SIGNATURE_INVALID);
        }

        String familyId = presented.getFamilyId() != null ? presented.getFamilyId() : UUID.randomUUID().toString();
        RefreshTokenEntity successor = refreshTokenRepository.save(RefreshTokenEntity.builder()
                .token(tokenService.generateRefreshToken(presented.getUser()))
                .user(presented.getUser())
                .deviceId(presented.getDeviceId())
                .activeRole(presented.getActiveRole())
                .expiryDate(presented.getExpiryDate()) // absolute lifetime of the login, not extended
                .familyId(familyId)
                .build());

        presented.setFamilyId(familyId);
        presented.setRotatedAt(now);
        presented.setReplacedById(successor.getId());
        refreshTokenRepository.save(presented);

        return issueCookies(successor, response, now);
    }

    /** Follows the rotation chain to the family's live token; null if the family has ended. */
    private RefreshTokenEntity currentOfFamily(RefreshTokenEntity rotated, Instant now) {
        RefreshTokenEntity t = rotated;
        for (int hop = 0; hop < 16 && t != null && t.isRotated(); hop++) {
            t = t.getReplacedById() == null ? null : refreshTokenRepository.findById(t.getReplacedById()).orElse(null);
        }
        if (t == null || t.isRotated() || t.getExpiryDate().isBefore(now)) {
            return null;
        }
        return t;
    }

    private String issueCookies(RefreshTokenEntity refresh, HttpServletResponse response, Instant now) {
        String newAccessToken = tokenService.generateAccessToken(refresh.getUser(), refresh.getActiveRole());
        long remaining = Math.max(60L, Duration.between(now, refresh.getExpiryDate()).getSeconds());

        CookieUtils.setCookie(response, CookieUtils.ACCESS_TOKEN_COOKIE, newAccessToken, 15 * 60);
        CookieUtils.setCookie(response, CookieUtils.REFRESH_TOKEN_COOKIE, refresh.getToken(),
                (int) Math.min(Integer.MAX_VALUE, remaining));
        return newAccessToken;
    }

    @Transactional
    public void logout(String refreshToken, HttpServletResponse response) {
        if (refreshToken != null) {
            // The whole rotation family ends with the session, including already-rotated ancestors.
            refreshTokenRepository.findByToken(refreshToken).ifPresent(t -> {
                if (t.getFamilyId() != null) {
                    refreshTokenRepository.deleteByFamilyId(t.getFamilyId());
                } else {
                    refreshTokenRepository.delete(t);
                }
            });
        }
        CookieUtils.clearCookie(response, CookieUtils.ACCESS_TOKEN_COOKIE);
        CookieUtils.clearCookie(response, CookieUtils.REFRESH_TOKEN_COOKIE);
    }


    public String resendVerificationCode(String email) {
        RegisterRecordEntity reg = registerRecordRepository.findByEmail(email)
                .orElseThrow(() -> new BadRequestException(Errors.REG_PENDING_NOT_FOUND));

        reg.setCode(codeGenService.generateRandomString());
        registerRecordRepository.save(reg);

        authEmailService.sendEmail(
                reg.getEmail(),
                emailMessages.getSubjectEmailConfirmation(),
                emailMessages.getEmailAddressConfirmationMessage(reg.getUuid(), reg.getCode())
        );

        return reg.getUuid();
    }


    public ResetPasswordInfo forgotPassword(ForgotPasswordRequest request) {
        UserEntity user = userRepository.findByEmail(request.email())
                .orElseThrow(EmailNotFoundException::new);

        PasswordResetEntity passwordResetEntity = PasswordResetEntity.builder()
                .uuid(UUID.randomUUID().toString())
                .email(user.getEmail())
                .build();

        passwordResetRepository.save(passwordResetEntity);

        return new ResetPasswordInfo(passwordResetEntity.getUuid(), passwordResetEntity.getEmail());
    }


    public void resetPassword(ResetPasswordRequest request) {
        PasswordResetEntity resetEntity = passwordResetRepository.findById(request.email())
                .orElseThrow(() -> new BadRequestException(Errors.PASSWORD_RESET_EXPIRED_LINK));

        if (!resetEntity.getUuid().equals(request.uuid())) {
            throw new BadRequestException(Errors.PASSWORD_RESET_INVALID_REQUEST);
        }

        UserEntity user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new InternalServerErrorException(Errors.INTERNAL_ERROR));

        // Skipped for a social-only account: there is no previous password to be the same as,
        // and this is the path by which such an account gains its first one.
        if (user.hasUsablePassword() && passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new SamePasswordException();
        }

        user.setPassword(passwordEncoder.encode(request.password()));
        userRepository.save(user);

        passwordResetRepository.deleteByEmail(request.email());
    }
}
