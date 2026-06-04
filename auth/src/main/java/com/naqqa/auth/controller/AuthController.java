package com.naqqa.auth.controller;

import com.naqqa.auth.config.auth.EmailMessages;
import com.naqqa.auth.dto.auth.*;
import com.naqqa.auth.service.auth.AuthService;
import com.naqqa.auth.service.auth.AuthEmailService;
import com.naqqa.auth.security.CookieUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.repository.UserRepository;
import com.naqqa.auth.service.security.DeviceSessionService;
import org.springframework.security.core.context.SecurityContextHolder;


@RestController
@RequiredArgsConstructor
@ConditionalOnMissingBean(AuthController.class)
public class AuthController {

    private final AuthService authService;
    private final AuthEmailService authEmailService;
    private final EmailMessages emailMessages;
    private final DeviceSessionService deviceSessionService;
    private final UserRepository userRepository;

    @PostMapping("/logout/device")
    public ResponseEntity<Void> logoutDevice(@RequestBody String deviceId) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        UserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() -> new com.naqqa.auth.exceptions.ResourceNotFoundException("User not found"));
        
        deviceSessionService.revokeDevice(user, deviceId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@RequestBody RegisterRequest request) {
        return ResponseEntity.ok(authService.register(request));
    }

    @PostMapping("/confirm-email")
    public ResponseEntity<?> confirmEmail(@RequestBody EmailConfirmationRequest request) {
        authService.confirmEmail(request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestParam(defaultValue = "false") boolean rememberMe, @RequestBody LoginRequest request, HttpServletResponse response) {
        AuthResponse authResponse = authService.login(request, rememberMe, response);
        return new ResponseEntity<>(authResponse, HttpStatus.OK);
    }

    @PostMapping("/refresh")
    public ResponseEntity<Void> refresh(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = CookieUtils.getCookieValue(request, CookieUtils.REFRESH_TOKEN_COOKIE);
        if (refreshToken == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        authService.refresh(refreshToken, response);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = CookieUtils.getCookieValue(request, CookieUtils.REFRESH_TOKEN_COOKIE);
        authService.logout(refreshToken, response);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<String> forgotPassword(@RequestBody ForgotPasswordRequest request) {
        ResetPasswordInfo info = authService.forgotPassword(request);

        authEmailService.sendEmail(
                request.email(),
                emailMessages.getResetPasswordSubject(),
                emailMessages.getResetPasswordEmailMessage(info.email(), info.uuid())
        );

        return new ResponseEntity<>(HttpStatus.OK);
    }

    @PostMapping("/reset-password")
    public void resetPassword(@RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
    }
}
