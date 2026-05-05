package com.naqqa.auth.service.auth;

import com.naqqa.auth.dto.auth.*;
import com.naqqa.auth.entity.auth.UserEntity;
import jakarta.servlet.http.HttpServletResponse;

public interface AuthService {
    RegisterResponse register(RegisterRequest request);

    UserEntity confirmEmail(EmailConfirmationRequest request);

    AuthResponse login(LoginRequest request, boolean rememberMe, HttpServletResponse response);

    String refresh(String refreshToken, HttpServletResponse response);

    void logout(String refreshToken, HttpServletResponse response);

    String resendVerificationCode(String email);

    ResetPasswordInfo forgotPassword(ForgotPasswordRequest request);

    void resetPassword(ResetPasswordRequest request);
}
