package com.naqqa.auth.controller;

import com.naqqa.auth.dto.auth.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("${auth.api.base-path:/api/auth}")
@RequiredArgsConstructor
@ConditionalOnMissingBean(AuthController.class)
public class DefaultAuthController {

    private final AuthController authController;

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
        return authController.register(request);
    }

    @PostMapping("/confirm-email")
    public ResponseEntity<?> confirmEmail(@RequestBody EmailConfirmationRequest request) {
        return authController.confirmEmail(request);
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(
            @RequestParam(defaultValue = "false") boolean rememberMe,
            @RequestBody LoginRequest request
    ) {
        return authController.login(rememberMe, request);
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody ForgotPasswordRequest request) {
        return authController.forgotPassword(request);
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody ResetPasswordRequest request) {
        authController.resetPassword(request);
        return ResponseEntity.ok().build();
    }
}
