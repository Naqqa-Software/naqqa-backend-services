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
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
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

    @Transactional
    public String refresh(String refreshToken, HttpServletResponse response) {
        RefreshTokenEntity storedToken = refreshTokenRepository.findByToken(refreshToken)
                .orElseThrow(() -> new BadRequestException(Errors.TOKEN_INVALID));

        if (storedToken.getExpiryDate().isBefore(Instant.now())) {
            refreshTokenRepository.delete(storedToken);
            throw new BadRequestException(Errors.TOKEN_EXPIRED);
        }

        if (!jwtService.isTokenValid(refreshToken, String.valueOf(storedToken.getUser().getId()), true)) {
            throw new BadRequestException(Errors.TOKEN_SIGNATURE_INVALID);
        }

        UserEntity user = storedToken.getUser();
        RoleEntity activeRole = storedToken.getActiveRole();
        String newAccessToken = tokenService.generateAccessToken(user, activeRole);

        CookieUtils.setCookie(response, CookieUtils.ACCESS_TOKEN_COOKIE, newAccessToken, 15 * 60);
        return newAccessToken;
    }

    @Transactional
    public void logout(String refreshToken, HttpServletResponse response) {
        if (refreshToken != null) {
            refreshTokenRepository.deleteByToken(refreshToken);
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

        if (passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new SamePasswordException();
        }

        user.setPassword(passwordEncoder.encode(request.password()));
        userRepository.save(user);

        passwordResetRepository.deleteByEmail(request.email());
    }
}
