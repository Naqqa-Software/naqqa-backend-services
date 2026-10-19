package com.naqqa.auth.service.security;

import com.naqqa.auth.config.auth.Errors;
import com.naqqa.auth.dto.auth.AuthResponse;
import com.naqqa.auth.entity.auth.RefreshTokenEntity;
import com.naqqa.auth.entity.auth.UserDeviceEntity;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.exceptions.BadRequestException;
import com.naqqa.auth.exceptions.auth.AccountBlockedException;
import com.naqqa.auth.repository.UserDeviceRepository;
import com.naqqa.auth.repository.UserRepository;
import com.naqqa.auth.roles.RoleProvider;
import com.naqqa.auth.security.CookieUtils;
import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.sso.mfa.StepUp;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthSessionIssuer {

    public static final String DEFAULT_DEVICE = "DEFAULT_DEVICE";
    public static final String DEFAULT_CLIENT_TYPE = "DEFAULT";
    public static final int ACCESS_COOKIE_SECONDS = 15 * 60;
    static final Duration LEGACY_REFRESH_LIFETIME = Duration.ofDays(30);

    public record Request(String deviceId, String clientType, String clientId, boolean rememberMe, String sessionId,
                          Collection<String> amr, String acr, Instant authTime, Duration accessTtl) {

        public static Request password(String deviceId, String clientType, boolean rememberMe, Instant now) {
            return new Request(deviceId, clientType, null, rememberMe, null, List.of("pwd"), StepUp.ACR_PASSWORD, now, null);
        }
    }

    public record Issued(AuthResponse response, UserEntity user, RoleEntity role, String accessToken, String refreshToken,
                         RefreshTokenEntity binding) {
    }

    private final UserRepository userRepository;
    private final UserDeviceRepository userDeviceRepository;
    private final DeviceSessionService deviceSessionService;
    private final RoleProvider roleProvider;
    private final TokenService tokenService;
    private final RefreshTokenManager refreshTokenManager;
    private final SsoProperties ssoProperties;
    private final ObjectProvider<AuthSessionHooks> hooks;
    private final ObjectProvider<CredentialChangeListener> credentialListeners;
    private final Clock clock;

    public static String deviceOrDefault(String deviceId) {
        return deviceId != null && !deviceId.isBlank() ? deviceId : DEFAULT_DEVICE;
    }

    public static String clientTypeOrDefault(String clientType) {
        return clientType != null && !clientType.isBlank() ? clientType : DEFAULT_CLIENT_TYPE;
    }

    public RoleEntity resolveActiveRole(UserEntity user, String deviceId, String clientType) {
        RoleEntity activeRole = null;
        UserDeviceEntity userDevice = userDeviceRepository.findByUserIdAndDeviceId(user.getId(), deviceId).orElse(null);
        if (userDevice != null) {
            RoleEntity lastRole = userDevice.getLastRole();
            if (lastRole != null) {
                boolean allowed = roleProvider.isRoleAllowed(clientType, lastRole.getName());
                boolean hasRole = user.getRoles().contains(lastRole);
                if (allowed && hasRole) {
                    activeRole = lastRole;
                }
            }
        }
        if (activeRole == null) {
            String fallbackRoleName = roleProvider.getFallbackRole(user, clientType);
            activeRole = user.getRoles().stream()
                    .filter(Objects::nonNull)
                    .filter(r -> r.getName().equals(fallbackRoleName))
                    .findFirst()
                    .orElse(null);
            if (activeRole == null || !roleProvider.isRoleAllowed(clientType, activeRole.getName())) {
                activeRole = user.getRoles().stream()
                        .filter(Objects::nonNull)
                        .filter(r -> roleProvider.isRoleAllowed(clientType, r.getName()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("No allowed roles found for user"));
            }
        }
        return activeRole;
    }

    public Issued issue(UserEntity user, Request req, HttpServletResponse response) {
        if (!user.canSignIn()) {
            // Last line for every sign-in path: no session for a disabled or blocked account.
            throw new AccountBlockedException();
        }
        String deviceId = deviceOrDefault(req.deviceId());
        String clientType = clientTypeOrDefault(req.clientType());
        RoleEntity activeRole = resolveActiveRole(user, deviceId, clientType);
        deviceSessionService.handleDeviceLogin(user, deviceId, clientType, activeRole);
        Instant authTime = req.authTime() != null ? req.authTime() : clock.instant();
        String accessToken = tokenService.generateAccessToken(user, activeRole,
                new TokenService.AccessContext(req.sessionId(), req.amr(), req.acr(), authTime, null, req.accessTtl()));
        RefreshTokenManager.Issued refresh = refreshTokenManager.issue(user, activeRole,
                new RefreshTokenManager.Binding(deviceId, req.clientId(), clientType, req.sessionId(), req.amr(), req.acr(), authTime, req.rememberMe()));
        setTokenCookies(response, accessToken, refresh.token());
        return new Issued(toResponse(user, activeRole), user, activeRole, accessToken, refresh.token(), refresh.binding());
    }

    public String reissueAccess(UserEntity user, Long activeRoleId, String sessionId, Collection<String> amr, String acr,
                                Instant authTime, HttpServletResponse response) {
        RoleEntity role = user.getRoles().stream()
                .filter(Objects::nonNull)
                .filter(r -> activeRoleId != null && activeRoleId.equals(r.getId()))
                .findFirst()
                .orElseGet(() -> resolveActiveRole(user, DEFAULT_DEVICE, DEFAULT_CLIENT_TYPE));
        String accessToken = tokenService.generateAccessToken(user, role,
                new TokenService.AccessContext(sessionId, amr, acr, authTime, null, ssoProperties.getSession().getAccessTokenTtl()));
        setTokenCookies(response, accessToken, null);
        return accessToken;
    }

    public void setTokenCookies(HttpServletResponse response, String accessToken, String refreshToken) {
        CookieUtils.setCookie(response, CookieUtils.ACCESS_TOKEN_COOKIE, accessToken, ACCESS_COOKIE_SECONDS);
        if (refreshToken != null) {
            CookieUtils.setCookie(response, CookieUtils.REFRESH_TOKEN_COOKIE, refreshToken,
                    (int) ssoProperties.getSession().getRefreshLifetime().getSeconds());
        }
    }

    public String refresh(String presented, HttpServletResponse response) {
        RefreshTokenManager.Rotation rotation = refreshTokenManager.rotate(presented);
        AuthSessionHooks hook = hooks.getIfAvailable();
        switch (rotation.outcome()) {
            case OK -> {
                RefreshTokenEntity binding = rotation.binding();
                UserEntity user = loadUser(binding);
                if (user == null || !user.canSignIn() || (hook != null && (!hook.refreshAllowed(binding) || !hook.refreshPermitted(user, binding)))) {
                    refreshTokenManager.revokeBinding(binding);
                    if (user != null && user.isBlocked()) {
                        // Blocked outside the admin API: end everything the account still holds on first contact.
                        revokeAllForUser(user, "account_blocked");
                    }
                    clearTokenCookies(response);
                    throw new BadRequestException(Errors.TOKEN_INVALID);
                }
                RoleEntity role = binding.getActiveRole();
                if (role == null || !user.getRoles().contains(role)) {
                    role = resolveActiveRole(user, deviceOrDefault(binding.getDeviceId()), clientTypeOrDefault(binding.getClientType()));
                    binding.setActiveRole(role);
                }
                Duration ttl = binding.getSessionId() != null ? ssoProperties.getSession().getAccessTokenTtl() : null;
                String accessToken = tokenService.generateAccessToken(user, role,
                        new TokenService.AccessContext(binding.getSessionId(), binding.getAmr(), binding.getAcr(),
                                binding.getAuthTime(), null, ttl));
                setTokenCookies(response, accessToken, rotation.token());
                if (hook != null) {
                    hook.onRefreshed(user, binding, false);
                }
                return accessToken;
            }
            case LEGACY -> {
                RefreshTokenEntity legacy = rotation.binding();
                UserEntity user = loadUser(legacy);
                if (user == null || !user.canSignIn() || (hook != null && !hook.refreshPermitted(user, legacy))) {
                    if (user != null && user.isBlocked()) {
                        revokeAllForUser(user, "account_blocked");
                    }
                    clearTokenCookies(response);
                    throw new BadRequestException(Errors.TOKEN_INVALID);
                }
                String deviceId = deviceOrDefault(legacy.getDeviceId());
                RoleEntity role = legacy.getActiveRole();
                if (role == null || !user.getRoles().contains(role)) {
                    role = resolveActiveRole(user, deviceId, DEFAULT_CLIENT_TYPE);
                }
                Instant issuedAt = legacy.getExpiryDate().minus(LEGACY_REFRESH_LIFETIME);
                Instant stale = clock.instant().minus(ssoProperties.getMfa().getStepUpMaxAge()).minusSeconds(1);
                Instant authTime = issuedAt.isBefore(stale) ? issuedAt : stale;
                String accessToken = tokenService.generateAccessToken(user, role,
                        new TokenService.AccessContext(null, List.of("pwd"), StepUp.ACR_PASSWORD, authTime, null, null));
                RefreshTokenManager.Issued issued = refreshTokenManager.issue(user, role,
                        new RefreshTokenManager.Binding(deviceId, null, DEFAULT_CLIENT_TYPE, null, List.of("pwd"), StepUp.ACR_PASSWORD, authTime, false));
                setTokenCookies(response, accessToken, issued.token());
                if (hook != null) {
                    hook.onRefreshed(user, issued.binding(), true);
                }
                return accessToken;
            }
            case REUSED -> {
                if (hook != null) {
                    hook.onRefreshReuse(rotation.binding());
                }
                clearTokenCookies(response);
                throw new BadRequestException(Errors.TOKEN_REUSED);
            }
            case EXPIRED -> {
                clearTokenCookies(response);
                throw new BadRequestException(Errors.TOKEN_EXPIRED);
            }
            default -> {
                clearTokenCookies(response);
                throw new BadRequestException(Errors.TOKEN_INVALID);
            }
        }
    }

    public void logout(String presented, HttpServletResponse response) {
        RefreshTokenEntity binding = presented == null ? null : refreshTokenManager.revoke(presented).orElse(null);
        AuthSessionHooks hook = hooks.getIfAvailable();
        if (hook != null) {
            hook.onLogout(binding, response);
        }
        clearTokenCookies(response);
    }

    public void logoutDevice(UserEntity user, String deviceId) {
        AuthSessionHooks hook = hooks.getIfAvailable();
        for (RefreshTokenEntity binding : refreshTokenManager.bindingsForDevice(user, deviceId)) {
            if (hook != null) {
                hook.onSessionEnded(binding);
            }
            refreshTokenManager.revokeBinding(binding);
        }
    }

    public void revokeAllForUser(UserEntity user, String reason) {
        refreshTokenManager.revokeAllForUser(user);
        credentialListeners.orderedStream().forEach(l -> l.onCredentialsChanged(user, reason));
    }

    public void clearTokenCookies(HttpServletResponse response) {
        CookieUtils.clearCookie(response, CookieUtils.ACCESS_TOKEN_COOKIE);
        CookieUtils.clearCookie(response, CookieUtils.REFRESH_TOKEN_COOKIE);
    }

    private UserEntity loadUser(RefreshTokenEntity binding) {
        if (binding == null || binding.getUser() == null || binding.getUser().getId() == null) {
            return null;
        }
        return userRepository.findById(binding.getUser().getId()).orElse(null);
    }

    public static AuthResponse toResponse(UserEntity user, RoleEntity activeRole) {
        return AuthResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .roles(user.getRoles().stream().filter(Objects::nonNull).map(RoleEntity::getName).collect(Collectors.toCollection(TreeSet::new)))
                .role(AuthResponse.RoleSummary.builder()
                        .id(activeRole.getId())
                        .name(activeRole.getName())
                        .build())
                .authorities(TokenService.authoritiesOf(user))
                .build();
    }
}
