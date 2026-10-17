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
import com.naqqa.auth.exceptions.auth.EmailNotFoundException;
import com.naqqa.auth.security.AuthPrincipal;
import com.naqqa.auth.exceptions.ForbiddenException;
import com.naqqa.auth.service.auth.SsoLoginHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.CompletableFuture;


@RequiredArgsConstructor
@ConditionalOnMissingBean(AuthController.class)
public class AuthController {

    private final AuthService authService;
    private final AuthEmailService authEmailService;
    private final EmailMessages emailMessages;
    private final DeviceSessionService deviceSessionService;
    private final UserRepository userRepository;
    private final ObjectProvider<SsoLoginHandler> ssoLoginHandler;

    @PostMapping("/logout/device")
    public ResponseEntity<Void> logoutDevice(@RequestBody String deviceId) {
        RequestChecks.present(deviceId);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (authentication.getPrincipal() instanceof AuthPrincipal principal && principal.isImpersonated()) {
            throw new ForbiddenException("IMPERSONATION_FORBIDDEN");
        }
        String name = authentication.getName();
        UserEntity user = (name != null && name.matches("\\d{1,18}") ? userRepository.findById(Long.valueOf(name)) : userRepository.findByEmail(name))
                .orElseThrow(() -> new com.naqqa.auth.exceptions.ResourceNotFoundException("User not found"));
        SsoLoginHandler sso = ssoLoginHandler.getIfAvailable();
        if (sso != null) {
            sso.logoutDevice(user, deviceId);
        }
        deviceSessionService.revokeDevice(user, deviceId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@RequestBody RegisterRequest request) {
        RequestChecks.present(RequestChecks.body(request).getEmail(), request.getPassword());
        limitAccountMail("register", request.getEmail());
        RegisterResponse registered = authService.register(request);
        SsoLoginHandler sso = ssoLoginHandler.getIfAvailable();
        if (sso != null) {
            sso.accountMailRequested("register", request.getEmail(), false);
        }
        return ResponseEntity.ok(registered);
    }

    private void limitAccountMail(String action, String email) {
        SsoLoginHandler sso = ssoLoginHandler.getIfAvailable();
        if (sso != null) {
            sso.limitAccountMail(action, email, ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest());
        }
    }

    @PostMapping("/confirm-email")
    public ResponseEntity<?> confirmEmail(@RequestBody EmailConfirmationRequest request) {
        RequestChecks.present(RequestChecks.body(request).getUuid(), request.getCode());
        authService.confirmEmail(request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestParam(defaultValue = "false") boolean rememberMe, @RequestBody LoginRequest request, HttpServletResponse response) {
        RequestChecks.present(RequestChecks.body(request).getEmail(), request.getPassword());
        SsoLoginHandler sso = ssoLoginHandler.getIfAvailable();
        if (sso != null) {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            return sso.login(request, rememberMe, attributes.getRequest(), response);
        }
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
        RequestChecks.present(RequestChecks.body(request).email());
        SsoLoginHandler sso = ssoLoginHandler.getIfAvailable();
        limitAccountMail("forgot-password", request.email());
        ResetPasswordInfo info;
        try {
            info = authService.forgotPassword(request);
        } catch (EmailNotFoundException e) {
            if (sso != null) {
                sso.accountMailRequested("forgot-password", request.email(), false);
            }
            if (sso != null && sso.enumerationSafe()) {
                return new ResponseEntity<>(HttpStatus.OK);
            }
            throw e;
        }
        if (sso != null) {
            sso.accountMailRequested("forgot-password", request.email(), true);
        }

        String subject = emailMessages.getResetPasswordSubject();
        String message = emailMessages.getResetPasswordEmailMessage(info.email(), info.uuid());
        if (sso != null && sso.enumerationSafe()) {
            CompletableFuture.runAsync(() -> authEmailService.sendEmail(info.email(), subject, message));
        } else {
            authEmailService.sendEmail(info.email(), subject, message);
        }

        return new ResponseEntity<>(HttpStatus.OK);
    }

    @PostMapping("/reset-password")
    public void resetPassword(@RequestBody ResetPasswordRequest request) {
        RequestChecks.present(RequestChecks.body(request).email(), request.uuid(), request.password());
        authService.resetPassword(request);
    }
}
