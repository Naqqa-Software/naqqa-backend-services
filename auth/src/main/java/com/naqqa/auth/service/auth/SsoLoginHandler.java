package com.naqqa.auth.service.auth;

import com.naqqa.auth.dto.auth.LoginRequest;
import com.naqqa.auth.entity.auth.UserEntity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;

public interface SsoLoginHandler {
    ResponseEntity<?> login(LoginRequest request, boolean rememberMe, HttpServletRequest httpRequest, HttpServletResponse response);

    boolean enumerationSafe();

    default void logoutDevice(UserEntity user, String deviceId) {
    }

    default void limitAccountMail(String action, String email, HttpServletRequest request) {
    }

    default void accountMailRequested(String action, String email, boolean known) {
    }
}
