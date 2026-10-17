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
import com.naqqa.auth.roles.RoleProvider;
import com.naqqa.auth.service.security.AuthAudit;
import com.naqqa.auth.service.security.AuthSessionIssuer;
import com.naqqa.auth.service.security.CodeGenService;
import com.naqqa.auth.service.security.PasswordService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
public class DefaultAuthService implements AuthService {

    public static final Duration PASSWORD_RESET_TTL = Duration.ofSeconds(1800);
    public static final int MAX_CONFIRM_ATTEMPTS = 5;
    static final String SEALED_PREFIX = "h:";
    static final String REGISTRATION_CODE = "registration";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordService passwordService;
    private final RegisterRecordRepository registerRecordRepository;
    private final PasswordResetRepository passwordResetRepository;
    private final RoleProvider roleProvider;
    private final CodeGenService codeGenService;
    private final AuthEmailService authEmailService;
    private final EmailMessages emailMessages;
    private final AuthSessionIssuer authSessionIssuer;
    private final Clock clock;
    private final AuthAudit audit;

    public DefaultAuthService(UserRepository userRepository, RoleRepository roleRepository, PasswordService passwordService,
                              RegisterRecordRepository registerRecordRepository, PasswordResetRepository passwordResetRepository,
                              RoleProvider roleProvider, CodeGenService codeGenService, AuthEmailService authEmailService,
                              EmailMessages emailMessages, AuthSessionIssuer authSessionIssuer, Clock clock) {
        this(userRepository, roleRepository, passwordService, registerRecordRepository, passwordResetRepository, roleProvider,
                codeGenService, authEmailService, emailMessages, authSessionIssuer, clock, null);
    }

    public DefaultAuthService(UserRepository userRepository, RoleRepository roleRepository, PasswordService passwordService,
                              RegisterRecordRepository registerRecordRepository, PasswordResetRepository passwordResetRepository,
                              RoleProvider roleProvider, CodeGenService codeGenService, AuthEmailService authEmailService,
                              EmailMessages emailMessages, AuthSessionIssuer authSessionIssuer, Clock clock, AuthAudit audit) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordService = passwordService;
        this.registerRecordRepository = registerRecordRepository;
        this.passwordResetRepository = passwordResetRepository;
        this.roleProvider = roleProvider;
        this.codeGenService = codeGenService;
        this.authEmailService = authEmailService;
        this.emailMessages = emailMessages;
        this.authSessionIssuer = authSessionIssuer;
        this.clock = clock;
        this.audit = audit;
    }

    public RegisterResponse register(RegisterRequest request) {
        passwordService.enforcePolicy(request.getPassword(), context(request.getEmail(), request.getFullName()));
        String email = UserRepository.normalizeEmail(request.getEmail());
        if (userRepository.emailTaken(email)) {
            throw new EmailInUseException();
        }

        RegisterRecordEntity reg = new RegisterRecordEntity();
        reg.setUuid(UUID.randomUUID().toString());
        reg.setFullName(request.getFullName());
        reg.setEmail(email);
        reg.setPassword(passwordService.hash(request.getPassword()));
        String code = codeGenService.generateRandomString();
        reg.setCode(passwordService.sealCode(REGISTRATION_CODE, reg.getUuid(), code));

        registerRecordRepository.save(reg);

        authEmailService.sendEmail(
                email,
                emailMessages.getSubjectEmailConfirmation(),
                emailMessages.getEmailAddressConfirmationMessage(reg.getUuid(), code)
        );

        RegisterResponse response = new RegisterResponse();
        response.setUuid(reg.getUuid());

        return response;
    }


    public UserEntity confirmEmail(EmailConfirmationRequest request) {

        RoleEntity userRole = roleRepository.findByName(roleProvider.getDefaultRole())
                .orElseThrow(() -> new InternalServerErrorException(Errors.INTERNAL_ERROR));

        RegisterRecordEntity reg = registerRecordRepository
                .findById(request.getUuid())
                .orElseThrow(() -> new BadRequestException(Errors.REG_PENDING_NOT_FOUND));

        if (!passwordService.codeMatches(reg.getCode(), REGISTRATION_CODE, reg.getUuid(), request.getCode())) {
            reg.setAttempts(reg.getAttempts() + 1);
            if (reg.getAttempts() >= MAX_CONFIRM_ATTEMPTS) {
                registerRecordRepository.deleteById(reg.getUuid());
            } else {
                registerRecordRepository.save(reg);
            }
            throw new BadRequestException(Errors.REG_CODE_INVALID);
        }
        if (userRepository.emailTaken(reg.getEmail())) {
            registerRecordRepository.deleteById(reg.getUuid());
            throw new EmailInUseException();
        }

        UserEntity user = UserEntity.builder()
                .fullName(reg.getFullName())
                .email(reg.getEmail())
                .password(reg.getPassword())
                .blocked(false)
                .emailVerified(true)
                .roles(Collections.singleton(userRole))
                .build();

        userRepository.save(user);
        registerRecordRepository.deleteById(reg.getUuid());
        if (audit != null) {
            String userId = String.valueOf(user.getId());
            audit.record("account.register", userId, userId, Map.of("source", "email_confirmation"));
        }

        return user;
    }


    public AuthResponse login(LoginRequest request, boolean rememberMe, HttpServletResponse response) {
        UserEntity user = userRepository.findByEmailAnyCase(request.getEmail()).orElse(null);

        if (user == null) {
            String email = UserRepository.normalizeEmail(request.getEmail());
            RegisterRecordEntity reg = email == null ? null : registerRecordRepository.findByEmail(email)
                    .orElse(null);

            if (reg != null) {
                String uuid = resendVerificationCode(email);
                throw new EmailNotVerifiedException(uuid);
            }

            passwordService.dummy(request.getPassword());
            throw new WrongCredentialsException();
        }

        if (!passwordService.matches(request.getPassword(), user.getPassword())) {
            throw new WrongCredentialsException();
        }

        if (!user.isEnabled()) {
            throw new AccountBlockedException();
        }

        if (passwordService.needsRehash(user.getPassword())) {
            String rehashed = passwordService.hash(request.getPassword());
            if (!userRepository.replacePassword(user.getId(), user.getPassword(), rehashed)) {
                throw new WrongCredentialsException();
            }
            user.setPassword(rehashed);
        }

        return authSessionIssuer.issue(user,
                AuthSessionIssuer.Request.password(request.getDeviceId(), request.getClientType(), rememberMe, clock.instant()),
                response).response();
    }

    public String refresh(String refreshToken, HttpServletResponse response) {
        return authSessionIssuer.refresh(refreshToken, response);
    }

    public void logout(String refreshToken, HttpServletResponse response) {
        authSessionIssuer.logout(refreshToken, response);
    }


    public String resendVerificationCode(String email) {
        RegisterRecordEntity reg = registerRecordRepository.findByEmail(email)
                .orElseThrow(() -> new BadRequestException(Errors.REG_PENDING_NOT_FOUND));

        String code = codeGenService.generateRandomString();
        reg.setCode(passwordService.sealCode(REGISTRATION_CODE, reg.getUuid(), code));
        registerRecordRepository.save(reg);

        authEmailService.sendEmail(
                reg.getEmail(),
                emailMessages.getSubjectEmailConfirmation(),
                emailMessages.getEmailAddressConfirmationMessage(reg.getUuid(), code)
        );

        return reg.getUuid();
    }


    public ResetPasswordInfo forgotPassword(ForgotPasswordRequest request) {
        UserEntity user = userRepository.findByEmailAnyCase(request.email())
                .orElseThrow(EmailNotFoundException::new);

        String token = UUID.randomUUID().toString();
        PasswordResetEntity passwordResetEntity = PasswordResetEntity.builder()
                .uuid(sealResetToken(token))
                .email(user.getEmail())
                .expiresAt(clock.instant().plus(PASSWORD_RESET_TTL))
                .build();

        passwordResetRepository.save(passwordResetEntity);

        return new ResetPasswordInfo(token, passwordResetEntity.getEmail());
    }


    public void resetPassword(ResetPasswordRequest request) {
        PasswordResetEntity resetEntity = passwordResetRepository.findById(request.email())
                .orElseThrow(() -> new BadRequestException(Errors.PASSWORD_RESET_EXPIRED_LINK));

        Instant expiresAt = resetEntity.getExpiresAt();
        if (expiresAt == null || !clock.instant().isBefore(expiresAt)) {
            passwordResetRepository.deleteById(request.email());
            throw new BadRequestException(Errors.PASSWORD_RESET_EXPIRED_LINK);
        }
        if (!resetTokenMatches(resetEntity.getUuid(), request.uuid())) {
            throw new BadRequestException(Errors.PASSWORD_RESET_INVALID_REQUEST);
        }

        UserEntity user = userRepository.findByEmailAnyCase(request.email()).orElse(null);
        if (user == null) {
            passwordResetRepository.deleteById(request.email());
            throw new BadRequestException(Errors.PASSWORD_RESET_EXPIRED_LINK);
        }

        if (passwordService.matches(request.password(), user.getPassword())) {
            throw new SamePasswordException();
        }
        passwordService.enforcePolicy(request.password(), context(user.getEmail(), user.getFullName()));

        passwordResetRepository.deleteById(request.email());

        user.setPassword(passwordService.hash(request.password()));
        user.setEmailVerified(true);
        Map<String, Object> changed = new LinkedHashMap<>();
        changed.put("password", user.getPassword());
        changed.put("emailVerified", true);
        userRepository.updateFields(user.getId(), changed);

        authSessionIssuer.revokeAllForUser(user, "password_reset");
    }

    static List<String> context(String... values) {
        List<String> out = new ArrayList<>();
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                out.add(v);
            }
        }
        return out;
    }

    static String sealResetToken(String token) {
        try {
            return SEALED_PREFIX + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean resetTokenMatches(String stored, String presented) {
        if (stored == null || presented == null) {
            return false;
        }
        return uuidMatches(stored, stored.startsWith(SEALED_PREFIX) ? sealResetToken(presented) : presented);
    }

    static boolean uuidMatches(String expected, String presented) {
        if (expected == null || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }
}
