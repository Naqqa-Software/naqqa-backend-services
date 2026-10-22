package com.naqqa.auth.service.auth;

import com.naqqa.auth.config.auth.Errors;
import com.naqqa.auth.entity.auth.RefreshTokenEntity;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.exceptionhandlers.GlobalExceptionHandler;
import com.naqqa.auth.exceptions.BadRequestException;
import com.naqqa.auth.repository.RefreshTokenRepository;
import com.naqqa.auth.security.JwtService;
import com.naqqa.auth.service.security.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefreshTokenRotationTest {

    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock TokenService tokenService;
    @Mock JwtService jwtService;
    @Mock com.naqqa.auth.repository.UserRepository userRepository;
    @Mock com.naqqa.auth.repository.RoleRepository roleRepository;
    @Mock com.naqqa.auth.repository.UserDeviceRepository userDeviceRepository;
    @Mock com.naqqa.auth.service.security.DeviceSessionService deviceSessionService;
    @Mock org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Mock com.naqqa.auth.repository.redis.RegisterRecordRepository registerRecordRepository;
    @Mock com.naqqa.auth.repository.redis.PasswordResetRepository passwordResetRepository;
    @Mock com.naqqa.auth.roles.RoleProvider roleProvider;
    @Mock com.naqqa.auth.service.security.CodeGenService codeGenService;
    @Mock AuthEmailService authEmailService;
    @Mock com.naqqa.auth.config.auth.EmailMessages emailMessages;

    @InjectMocks DefaultAuthService service;

    private final UserEntity user = UserEntity.builder().id(7L).email("u@test").build();
    private final RoleEntity role = new RoleEntity();
    private final Instant familyExpiry = Instant.now().plus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    private long nextId = 100;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "refreshReuseGraceSeconds", 20L);
        role.setName("STUDENT");
        when(tokenService.generateAccessToken(any(), any())).thenReturn("new-access");
        when(tokenService.generateRefreshToken(any())).thenReturn("R2");
        when(jwtService.isTokenValid(anyString(), anyString(), anyBoolean())).thenReturn(true);
        when(refreshTokenRepository.save(any(RefreshTokenEntity.class))).thenAnswer(inv -> {
            RefreshTokenEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(nextId++);
            return e;
        });
    }

    private RefreshTokenEntity token(long id, String value, String family) {
        return RefreshTokenEntity.builder().id(id).token(value).user(user).deviceId("dev-1")
                .activeRole(role).expiryDate(familyExpiry).familyId(family).build();
    }

    private static List<String> setCookies(MockHttpServletResponse res) {
        return res.getHeaders("Set-Cookie");
    }

    @Test
    void refreshRotatesTokenKeepingFamilyExpiryDeviceAndRole() {
        RefreshTokenEntity r1 = token(1, "R1", "fam-1");
        when(refreshTokenRepository.findByTokenForUpdate("R1")).thenReturn(Optional.of(r1));
        MockHttpServletResponse res = new MockHttpServletResponse();

        assertThat(service.refresh("R1", res)).isEqualTo("new-access");

        ArgumentCaptor<RefreshTokenEntity> saved = ArgumentCaptor.forClass(RefreshTokenEntity.class);
        verify(refreshTokenRepository, times(2)).save(saved.capture());
        RefreshTokenEntity successor = saved.getAllValues().get(0);
        assertThat(successor.getToken()).isEqualTo("R2");
        assertThat(successor.getFamilyId()).isEqualTo("fam-1");
        assertThat(successor.getExpiryDate()).isEqualTo(familyExpiry);
        assertThat(successor.getDeviceId()).isEqualTo("dev-1");
        assertThat(successor.getActiveRole()).isSameAs(role);
        assertThat(successor.isRotated()).isFalse();

        assertThat(r1.isRotated()).isTrue();
        assertThat(r1.getReplacedById()).isEqualTo(successor.getId());

        assertThat(setCookies(res)).anyMatch(c -> c.startsWith("refresh_token=R2;"));
        assertThat(setCookies(res)).anyMatch(c -> c.startsWith("access_token=new-access;"));
        String refreshCookie = setCookies(res).stream().filter(c -> c.startsWith("refresh_token=")).findFirst().orElseThrow();
        long maxAge = Long.parseLong(refreshCookie.replaceAll(".*Max-Age=(\\d+).*", "$1"));
        assertThat(maxAge).isBetween(10L * 86400 - 60, 10L * 86400);
        verify(refreshTokenRepository, never()).deleteByFamilyId(anyString());
    }

    @Test
    void legacyTokenWithoutFamilyStartsOneOnFirstRefresh() {
        RefreshTokenEntity r1 = token(1, "R1", null);
        when(refreshTokenRepository.findByTokenForUpdate("R1")).thenReturn(Optional.of(r1));

        service.refresh("R1", new MockHttpServletResponse());

        assertThat(r1.getFamilyId()).isNotBlank();
        ArgumentCaptor<RefreshTokenEntity> saved = ArgumentCaptor.forClass(RefreshTokenEntity.class);
        verify(refreshTokenRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getFamilyId()).isEqualTo(r1.getFamilyId());
    }

    @Test
    void rotatedTokenWithinGraceReturnsTheCurrentPairWithoutRevoking() {
        RefreshTokenEntity r1 = token(1, "R1", "fam-1");
        r1.setRotatedAt(Instant.now().minusSeconds(5));
        r1.setReplacedById(2L);
        RefreshTokenEntity r2 = token(2, "R2", "fam-1");
        when(refreshTokenRepository.findByTokenForUpdate("R1")).thenReturn(Optional.of(r1));
        when(refreshTokenRepository.findById(2L)).thenReturn(Optional.of(r2));
        MockHttpServletResponse res = new MockHttpServletResponse();

        assertThat(service.refresh("R1", res)).isEqualTo("new-access");

        assertThat(setCookies(res)).anyMatch(c -> c.startsWith("refresh_token=R2;"));
        verify(refreshTokenRepository, never()).save(any());
        verify(refreshTokenRepository, never()).deleteByFamilyId(anyString());
    }

    @Test
    void graceFollowsTheChainToTheLiveToken() {
        RefreshTokenEntity r1 = token(1, "R1", "fam-1");
        r1.setRotatedAt(Instant.now().minusSeconds(8));
        r1.setReplacedById(2L);
        RefreshTokenEntity r2 = token(2, "R2", "fam-1");
        r2.setRotatedAt(Instant.now().minusSeconds(2));
        r2.setReplacedById(3L);
        RefreshTokenEntity r3 = token(3, "R3", "fam-1");
        when(refreshTokenRepository.findByTokenForUpdate("R1")).thenReturn(Optional.of(r1));
        when(refreshTokenRepository.findById(2L)).thenReturn(Optional.of(r2));
        when(refreshTokenRepository.findById(3L)).thenReturn(Optional.of(r3));
        MockHttpServletResponse res = new MockHttpServletResponse();

        service.refresh("R1", res);

        assertThat(setCookies(res)).anyMatch(c -> c.startsWith("refresh_token=R3;"));
    }

    @Test
    void reuseAfterGraceRevokesTheWholeFamily() {
        RefreshTokenEntity r1 = token(1, "R1", "fam-1");
        r1.setRotatedAt(Instant.now().minusSeconds(60));
        r1.setReplacedById(2L);
        when(refreshTokenRepository.findByTokenForUpdate("R1")).thenReturn(Optional.of(r1));
        MockHttpServletResponse res = new MockHttpServletResponse();

        assertThatThrownBy(() -> service.refresh("R1", res))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(Errors.TOKEN_REUSED);

        verify(refreshTokenRepository).deleteByFamilyId("fam-1");
        verify(refreshTokenRepository, never()).save(any());
        assertThat(setCookies(res)).anyMatch(c -> c.startsWith("refresh_token=;") && c.contains("Max-Age=0"));
        assertThat(setCookies(res)).anyMatch(c -> c.startsWith("access_token=;") && c.contains("Max-Age=0"));
    }

    @Test
    void rotatedTokenWithinGraceOfAnEndedFamilyIsInvalid() {
        RefreshTokenEntity r1 = token(1, "R1", "fam-1");
        r1.setRotatedAt(Instant.now().minusSeconds(3));
        r1.setReplacedById(2L);
        when(refreshTokenRepository.findByTokenForUpdate("R1")).thenReturn(Optional.of(r1));
        when(refreshTokenRepository.findById(2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh("R1", new MockHttpServletResponse()))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(Errors.TOKEN_INVALID);
        verify(refreshTokenRepository).deleteByFamilyId("fam-1");
    }

    @Test
    void unknownTokenIsInvalid() {
        when(refreshTokenRepository.findByTokenForUpdate("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.refresh("nope", new MockHttpServletResponse()))
                .hasMessage(Errors.TOKEN_INVALID);
    }

    @Test
    void expiredTokenIsDeletedAndRefused() {
        RefreshTokenEntity r1 = token(1, "R1", "fam-1");
        r1.setExpiryDate(Instant.now().minusSeconds(1));
        when(refreshTokenRepository.findByTokenForUpdate("R1")).thenReturn(Optional.of(r1));

        assertThatThrownBy(() -> service.refresh("R1", new MockHttpServletResponse()))
                .hasMessage(Errors.TOKEN_EXPIRED);
        verify(refreshTokenRepository).delete(r1);
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void logoutRevokesTheWholeFamily() {
        when(refreshTokenRepository.findByToken("R2")).thenReturn(Optional.of(token(2, "R2", "fam-1")));
        MockHttpServletResponse res = new MockHttpServletResponse();

        service.logout("R2", res);

        verify(refreshTokenRepository).deleteByFamilyId("fam-1");
        assertThat(setCookies(res)).anyMatch(c -> c.startsWith("refresh_token=;") && c.contains("Max-Age=0"));
    }

    @Test
    void logoutOfALegacyTokenDeletesJustThatToken() {
        RefreshTokenEntity legacy = token(2, "R2", null);
        when(refreshTokenRepository.findByToken("R2")).thenReturn(Optional.of(legacy));

        service.logout("R2", new MockHttpServletResponse());

        verify(refreshTokenRepository).delete(legacy);
        verify(refreshTokenRepository, never()).deleteByFamilyId(any());
    }

    @Test
    void tokenErrorsAreAnswered401() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        assertThat(handler.handleBadRequest(new BadRequestException(Errors.TOKEN_REUSED)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(handler.handleBadRequest(new BadRequestException(Errors.TOKEN_INVALID)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(handler.handleBadRequest(new BadRequestException("Auth.Errors.Something")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void refreshTokensAreUniqueEvenWithinTheSameSecond() {
        JwtService jwt = new JwtService();
        String key = "YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=";
        ReflectionTestUtils.setField(jwt, "secretKey", key);
        ReflectionTestUtils.setField(jwt, "refreshSecretKey", key);
        String a = jwt.generateRefreshToken("7");
        String b = jwt.generateRefreshToken("7");
        assertThat(a).isNotEqualTo(b);
        assertThat(jwt.isTokenValid(a, "7", true)).isTrue();
        String jti = jwt.extractClaim(a, io.jsonwebtoken.Claims::getId, true);
        assertThat(jti).isNotBlank();
        verifyNoInteractions(userRepository);
        verify(refreshTokenRepository, never()).deleteByFamilyId(eq("x"));
    }
}
