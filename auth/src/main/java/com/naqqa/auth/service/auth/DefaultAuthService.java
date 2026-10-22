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
    private final com.naqqa.auth.service.security.AuthAttemptLimiter attemptLimiter;

    static final int RESENDS_PER_EMAIL = 3;
    static final int RESENDS_PER_SOURCE = 3;
    static final long RESEND_WINDOW_MS = 60 * 60_000L;
    private volatile String dummyHash;

    @Value("${jwt.refresh-reuse-grace-seconds:20}")
    private long refreshReuseGraceSeconds = 20;

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        com.naqqa.auth.service.security.PasswordRules.validate(request.getPassword());
        if (userRepository.existsByEmail(request.getEmail())) {
            if (attemptLimiter.tryAcquire("register-exists", request.getEmail(), RESENDS_PER_EMAIL, RESEND_WINDOW_MS)) {
                authEmailService.sendEmail(request.getEmail(), emailMessages.getAlreadyRegisteredSubject(),
                        emailMessages.getAlreadyRegisteredMessage());
            }
            RegisterResponse response = new RegisterResponse();
            response.setUuid(UUID.randomUUID().toString());
            return response;
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

            String hash = reg != null ? reg.getPassword() : dummyHash();
            boolean passwordOk = request.getPassword() != null && passwordEncoder.matches(request.getPassword(), hash);
            if (reg != null && passwordOk) {
                boolean resend = attemptLimiter.tryAcquire("resend", request.getEmail(), RESENDS_PER_EMAIL, RESEND_WINDOW_MS)
                        && attemptLimiter.tryAcquire("resend-src", request.getEmail() + "|" + clientIp(),
                        RESENDS_PER_SOURCE, RESEND_WINDOW_MS);
                String uuid = resend ? resendVerificationCode(request.getEmail()) : reg.getUuid();
                throw new EmailNotVerifiedException(uuid);
            }

            throw new WrongCredentialsException();
        }

        if (!user.hasUsablePassword()
                || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new WrongCredentialsException();
        }

        if (!user.canSignIn()) {
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

    @Transactional(noRollbackFor = BadRequestException.class)
    public String refresh(String refreshToken, HttpServletResponse response) {
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
                .expiryDate(presented.getExpiryDate())
                .familyId(familyId)
                .build());

        presented.setFamilyId(familyId);
        presented.setRotatedAt(now);
        presented.setReplacedById(successor.getId());
        refreshTokenRepository.save(presented);

        return issueCookies(successor, response, now);
    }

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


    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        PasswordResetEntity resetEntity = passwordResetRepository.findById(request.email())
                .orElseThrow(() -> new BadRequestException(Errors.PASSWORD_RESET_EXPIRED_LINK));

        if (!resetEntity.getUuid().equals(request.uuid())) {
            throw new BadRequestException(Errors.PASSWORD_RESET_INVALID_REQUEST);
        }

        UserEntity user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new InternalServerErrorException(Errors.INTERNAL_ERROR));

        if (user.hasUsablePassword() && passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new SamePasswordException();
        }
        com.naqqa.auth.service.security.PasswordRules.validate(request.password());

        user.setPassword(passwordEncoder.encode(request.password()));
        userRepository.save(user);
        refreshTokenRepository.deleteByUser(user);

        passwordResetRepository.deleteByEmail(request.email());
    }

    private String dummyHash() {
        String hash = dummyHash;
        if (hash == null) {
            hash = passwordEncoder.encode(UUID.randomUUID().toString());
            dummyHash = hash;
        }
        return hash;
    }

    private static String clientIp() {
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet) {
            return servlet.getRequest().getRemoteAddr();
        }
        return "";
    }
}
