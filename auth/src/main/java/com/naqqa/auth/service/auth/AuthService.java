package com.naqqa.auth.service.auth;

import com.naqqa.auth.dto.auth.*;
import com.naqqa.auth.entity.auth.UserEntity;

public interface AuthService {
    RegisterResponse register(RegisterRequest request);

    UserEntity confirmEmail(EmailConfirmationRequest request);

    AuthResponse login(LoginRequest request, boolean rememberMe);

    String resendVerificationCode(String email);

    ResetPasswordInfo forgotPassword(ForgotPasswordRequest request);

    void resetPassword(ResetPasswordRequest request);
}
