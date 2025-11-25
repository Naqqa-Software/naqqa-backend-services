package com.naqqa.auth.service;

import com.naqqa.auth.dto.*;
import com.naqqa.auth.entity.UserEntity;

public interface AuthService {
    RegisterResponse register(RegisterRequest request);

    UserEntity confirmEmail(EmailConfirmationRequest request);

    AuthResponse login(LoginRequest request, boolean rememberMe);

    String resendVerificationCode(String email);

    ResetPasswordInfo forgotPassword(ForgotPasswordRequest request);

    void resetPassword(ResetPasswordRequest request);
}
