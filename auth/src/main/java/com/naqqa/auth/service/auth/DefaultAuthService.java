package com.naqqa.auth.service.auth;

import com.naqqa.auth.config.auth.EmailMessages;
import com.naqqa.auth.config.auth.Errors;
import com.naqqa.auth.dto.auth.*;
import com.naqqa.auth.entity.auth.PasswordResetEntity;
import com.naqqa.auth.entity.auth.RefreshTokenEntity;
import com.naqqa.auth.entity.auth.RegisterRecordEntity;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.exceptions.BadRequestException;
import com.naqqa.auth.exceptions.auth.*;
import com.naqqa.auth.repository.*;
import com.naqqa.auth.repository.redis.PasswordResetRepository;
import com.naqqa.auth.repository.redis.RegisterRecordRepository;
import com.naqqa.auth.security.CookieUtils;
import com.naqqa.auth.security.JwtService;
import com.naqqa.auth.roles.RoleProvider;
import com.naqqa.auth.service.security.CodeGenService;
import com.naqqa.auth.service.security.TokenService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@ConditionalOnMissingBean(AuthService.class)
public class DefaultAuthService implements AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
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
                emailMessages.getEmailAddressConfirmationMessage(reg.getCode())
        );

        RegisterResponse response = new RegisterResponse();
        response.setUuid(reg.getUuid());

        return response;
    }


    @Transactional
    public UserEntity confirmEmail(EmailConfirmationRequest request) {

        RoleEntity userRole = roleRepository.findByName(roleProvider.getDefaultRole())
                .orElseThrow(() -> new IllegalStateException("Default role 'USER' not found in database."));

        RegisterRecordEntity reg = registerRecordRepository
                .findById(request.getUuid())
                .orElseThrow(() -> new RuntimeException("Invalid UUID"));

        if (!reg.getCode().equals(request.getCode())) {
            throw new RuntimeException("Invalid code");
        }

        UserEntity user = UserEntity.builder()
                .fullName(reg.getFullName())
                .email(reg.getEmail())
                .password(reg.getPassword())
                .enabled(true)
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

        String accessToken = tokenService.generateAccessToken(user);
        String refreshToken = tokenService.generateRefreshToken(user);

        RefreshTokenEntity refreshTokenEntity = RefreshTokenEntity.builder()
                .token(refreshToken)
                .user(user)
                .expiryDate(Instant.now().plus(30, ChronoUnit.DAYS))
                .build();

        refreshTokenRepository.save(refreshTokenEntity);
        
        // Access token: 15 min (900s)
        CookieUtils.setCookie(response, CookieUtils.ACCESS_TOKEN_COOKIE, accessToken, 15 * 60);
        // Refresh token: 30 days
        CookieUtils.setCookie(response, CookieUtils.REFRESH_TOKEN_COOKIE, refreshToken, 30 * 24 * 60 * 60);

        // Populate authorities matching TokenService logic
        Set<String> authorities = java.util.stream.Stream.concat(
                user.getLastRole().getAuthorities().stream().map(com.naqqa.auth.entity.authorities.AuthorityEntity::getName),
                user.getSubRoles().stream().flatMap(sr -> sr.getAuthorities().stream()).map(com.naqqa.auth.entity.authorities.AuthorityEntity::getName)
        ).collect(java.util.stream.Collectors.toSet());

        return AuthResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .roles(user.getRoles().stream().map(RoleEntity::getName).collect(java.util.stream.Collectors.toSet()))
                .role(AuthResponse.RoleSummary.builder()
                        .id(user.getLastRole().getId())
                        .name(user.getLastRole().getName())
                        .build())
                .authorities(authorities)
                .build();
    }

    @Transactional
    public String refresh(String refreshToken, HttpServletResponse response) {
        RefreshTokenEntity storedToken = refreshTokenRepository.findByToken(refreshToken)
                .orElseThrow(() -> new RuntimeException("Invalid refresh token"));

        if (storedToken.getExpiryDate().isBefore(Instant.now())) {
            refreshTokenRepository.delete(storedToken);
            throw new RuntimeException("Refresh token expired");
        }

        // Validate signature and subject
        if (!jwtService.isTokenValid(refreshToken, String.valueOf(storedToken.getUser().getId()), true)) {
            throw new RuntimeException("Invalid refresh token signature");
        }

        UserEntity user = storedToken.getUser();
        String newAccessToken = tokenService.generateAccessToken(user);

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
        RegisterRecordEntity reg = (RegisterRecordEntity) registerRecordRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("No registration found"));

        reg.setCode(codeGenService.generateRandomString());
        registerRecordRepository.save(reg);

        authEmailService.sendEmail(
                reg.getEmail(),
                emailMessages.getSubjectEmailConfirmation(),
                emailMessages.getEmailAddressConfirmationMessage(reg.getCode())
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
                .orElseThrow();

        if (passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new SamePasswordException();
        }

        user.setPassword(passwordEncoder.encode(request.password()));
        userRepository.save(user);

        passwordResetRepository.deleteByEmail(request.email());
    }
}
