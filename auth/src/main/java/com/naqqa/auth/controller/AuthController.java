package com.naqqa.auth.controller;

import com.naqqa.auth.config.EmailMessages;
import com.naqqa.auth.dto.*;
import com.naqqa.auth.service.AuthService;
import com.naqqa.auth.service.AuthEmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@RequiredArgsConstructor
@ConditionalOnMissingBean(AuthController.class)
public class AuthController {

    private final AuthService authService;
    private final AuthEmailService authEmailService;
    private final EmailMessages emailMessages;

    public ResponseEntity<RegisterResponse> register(RegisterRequest request) {
        RegisterResponse uuid = authService.register(request);
        return new ResponseEntity<>(uuid, HttpStatus.CREATED);
    }

    public ResponseEntity<?> confirmEmail(EmailConfirmationRequest request) {
        authService.confirmEmail(request);
        return ResponseEntity.ok().build();
    }

    public ResponseEntity<?> login(boolean rememberMe, LoginRequest request) {
        AuthResponse authResponse = authService.login(request, rememberMe);
        return new ResponseEntity<>(authResponse, HttpStatus.CREATED);
    }

    public ResponseEntity<String> forgotPassword(ForgotPasswordRequest request) {
        ResetPasswordInfo info = authService.forgotPassword(request);

        authEmailService.sendEmail(
                request.email(),
                emailMessages.getResetPasswordSubject(),
                emailMessages.getResetPasswordEmailMessage(info.email(), info.uuid())
        );


        return new ResponseEntity<>(HttpStatus.OK);
    }

    public void resetPassword(ResetPasswordRequest request) {
        authService.resetPassword(request);
    }
}
