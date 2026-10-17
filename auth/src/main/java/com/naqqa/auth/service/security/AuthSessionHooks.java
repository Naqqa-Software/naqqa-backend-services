package com.naqqa.auth.service.security;

import com.naqqa.auth.entity.auth.RefreshTokenEntity;
import com.naqqa.auth.entity.auth.UserEntity;
import jakarta.servlet.http.HttpServletResponse;

public interface AuthSessionHooks {

    default boolean refreshAllowed(RefreshTokenEntity binding) {
        return true;
    }

    default boolean refreshPermitted(UserEntity user, RefreshTokenEntity binding) {
        return true;
    }

    default void onRefreshed(UserEntity user, RefreshTokenEntity binding, boolean legacy) {
    }

    default void onRefreshReuse(RefreshTokenEntity binding) {
    }

    default void onLogout(RefreshTokenEntity binding, HttpServletResponse response) {
    }

    default void onSessionEnded(RefreshTokenEntity binding) {
    }
}
