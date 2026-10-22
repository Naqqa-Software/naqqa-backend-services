package com.naqqa.auth.service.security;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.exceptions.auth.AccountBlockedException;
import com.naqqa.auth.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class TokenServiceBlockedAccountTest {

    private final JwtEncoder encoder = mock(JwtEncoder.class);
    private final TokenService tokens = new TokenService(encoder, mock(JwtService.class));

    @Test
    void blockedAccountGetsNoAccessToken() {
        UserEntity user = UserEntity.builder().id(1L).email("a@test").blocked(true).build();
        assertFalse(user.canSignIn());
        assertThrows(AccountBlockedException.class, () -> tokens.generateAccessToken(user, new RoleEntity()));
        verifyNoInteractions(encoder);
    }

    @Test
    void disabledAccountGetsNoAccessToken() {
        UserEntity user = UserEntity.builder().id(2L).email("b@test").enabled(false).build();
        assertFalse(user.canSignIn());
        assertThrows(AccountBlockedException.class, () -> tokens.generateAccessToken(user, new RoleEntity()));
        verifyNoInteractions(encoder);
    }

    @Test
    void activeAccountCanSignIn() {
        assertTrue(UserEntity.builder().id(3L).email("c@test").build().canSignIn());
    }
}
