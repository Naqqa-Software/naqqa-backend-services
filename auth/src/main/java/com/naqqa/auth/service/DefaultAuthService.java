package com.naqqa.auth.service;

import com.naqqa.auth.config.EmailMessages;
import com.naqqa.auth.config.Errors;
import com.naqqa.auth.dto.*;
import com.naqqa.auth.entity.*;
import com.naqqa.auth.exceptions.BadRequestException;
import com.naqqa.auth.exceptions.auth.*;
import com.naqqa.auth.repository.*;
import com.naqqa.auth.repository.redis.PasswordResetRepository;
import com.naqqa.auth.repository.redis.RegisterRecordRepository;
import com.naqqa.auth.security.JwtService;
import com.naqqa.auth.roles.RoleProvider;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@ConditionalOnMissingBean(AuthService.class)
public class DefaultAuthService implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RegisterRecordRepository registerRecordRepository;
    private final PasswordResetRepository passwordResetRepository;
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

        RegisterRecordEntity reg = registerRecordRepository
                .findById(request.getUuid())
                .orElseThrow(() -> new RuntimeException("Invalid UUID"));

        if (!reg.getCode().equals(request.getCode())) {
            throw new RuntimeException("Invalid code");
        }

        UserEntity user = new UserEntity();
        user.setFullName(reg.getFullName());
        user.setEmail(reg.getEmail());
        user.setPassword(reg.getPassword());
        user.setEnabled(true);
        user.setRole(roleProvider.getDefaultRole());

        userRepository.save(user);

        // Delete temp data
        registerRecordRepository.deleteById(reg.getUuid());

        return user;
    }


    @Transactional
    public AuthResponse login(LoginRequest request, boolean rememberMe) {
        UserEntity user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(WrongCredentialsException::new);

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new WrongCredentialsException();
        }

        if (!user.isEnabled()) {
            String uuid = resendVerificationCode(user.getEmail());
            throw new EmailNotVerifiedException(uuid);
        }

        String jwtToken = rememberMe
                ? tokenService.generateLongLivedToken(user)
                : tokenService.generateShortLivedToken(user);

        return new AuthResponse(jwtToken);
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
