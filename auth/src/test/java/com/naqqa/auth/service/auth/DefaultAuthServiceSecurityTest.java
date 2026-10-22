package com.naqqa.auth.service.auth;

import com.naqqa.auth.config.auth.EmailMessages;
import com.naqqa.auth.dto.auth.LoginRequest;
import com.naqqa.auth.dto.auth.RegisterRequest;
import com.naqqa.auth.dto.auth.RegisterResponse;
import com.naqqa.auth.entity.auth.RegisterRecordEntity;
import com.naqqa.auth.exceptions.BadRequestException;
import com.naqqa.auth.exceptions.auth.EmailNotVerifiedException;
import com.naqqa.auth.exceptions.auth.WrongCredentialsException;
import com.naqqa.auth.repository.RefreshTokenRepository;
import com.naqqa.auth.repository.RoleRepository;
import com.naqqa.auth.repository.UserDeviceRepository;
import com.naqqa.auth.repository.UserRepository;
import com.naqqa.auth.repository.redis.PasswordResetRepository;
import com.naqqa.auth.repository.redis.RegisterRecordRepository;
import com.naqqa.auth.roles.RoleProvider;
import com.naqqa.auth.security.JwtService;
import com.naqqa.auth.service.security.AuthAttemptLimiter;
import com.naqqa.auth.service.security.CodeGenService;
import com.naqqa.auth.service.security.DeviceSessionService;
import com.naqqa.auth.service.security.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultAuthServiceSecurityTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final UserRepository users = mock(UserRepository.class);
    private final RegisterRecordRepository registrations = mock(RegisterRecordRepository.class);
    private final AuthEmailService mail = mock(AuthEmailService.class);
    private final EmailMessages messages = new EmailMessages("https://app", "App", "#000");
    private final DefaultAuthService service = new DefaultAuthService(users, mock(RoleRepository.class),
            mock(UserDeviceRepository.class), mock(DeviceSessionService.class), encoder, registrations,
            mock(PasswordResetRepository.class), mock(RefreshTokenRepository.class), mock(JwtService.class),
            mock(RoleProvider.class), mock(TokenService.class), mock(CodeGenService.class), mail, messages,
            new AuthAttemptLimiter());

    private static RegisterRequest register(String email, String password) {
        RegisterRequest r = new RegisterRequest();
        r.setEmail(email);
        r.setPassword(password);
        r.setFullName("Ana");
        return r;
    }

    private static LoginRequest login(String email, String password) {
        LoginRequest r = new LoginRequest();
        r.setEmail(email);
        r.setPassword(password);
        return r;
    }

    @Test
    void registerIsUniformForExistingAddresses() {
        when(users.existsByEmail("taken@x.md")).thenReturn(true);
        RegisterResponse response = service.register(register("taken@x.md", "verde-Chisinau-42"));
        assertNotEquals(null, response.getUuid());
        verify(registrations, never()).save(any());
        verify(mail).sendEmail(eq("taken@x.md"), eq(messages.getAlreadyRegisteredSubject()), anyString());
    }

    @Test
    void weakPasswordsAreRejectedOnRegisterAndReset() {
        assertThrows(BadRequestException.class, () -> service.register(register("new@x.md", "short")));
        assertThrows(BadRequestException.class, () -> service.register(register("new@x.md", "password123")));
    }

    @Test
    void pendingRegistrationIsRevealedOnlyWithTheRightPassword() {
        RegisterRecordEntity reg = new RegisterRecordEntity();
        reg.setUuid("pending-uuid");
        reg.setEmail("pending@x.md");
        reg.setPassword(encoder.encode("verde-Chisinau-42"));
        reg.setCode("ABC123");
        when(users.findByEmail("pending@x.md")).thenReturn(Optional.empty());
        when(registrations.findByEmail("pending@x.md")).thenReturn(Optional.of(reg));

        assertThrows(WrongCredentialsException.class, () -> service.login(login("pending@x.md", "wrong-guess-1"), false, null));
        verify(mail, never()).sendEmail(anyString(), anyString(), anyString());

        EmailNotVerifiedException e = assertThrows(EmailNotVerifiedException.class,
                () -> service.login(login("pending@x.md", "verde-Chisinau-42"), false, null));
        assertEquals("pending-uuid", e.getUuid());

        for (int i = 0; i < 5; i++) {
            assertThrows(EmailNotVerifiedException.class, () -> service.login(login("pending@x.md", "verde-Chisinau-42"), false, null));
        }
        verify(mail, times(3)).sendEmail(eq("pending@x.md"), anyString(), anyString());
    }

    @Test
    void unknownAddressFailsLikeAWrongPassword() {
        when(users.findByEmail("nobody@x.md")).thenReturn(Optional.empty());
        when(registrations.findByEmail("nobody@x.md")).thenReturn(Optional.empty());
        assertThrows(WrongCredentialsException.class, () -> service.login(login("nobody@x.md", "anything-123"), false, null));
    }
}
