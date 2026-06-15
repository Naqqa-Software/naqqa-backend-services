package com.naqqa.auth.security.oauth2;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.security.CookieUtils;
import com.naqqa.auth.service.security.TokenService;
import com.naqqa.auth.service.social.SocialAuthService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class OAuth2AuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final SocialAuthService socialAuthService;
    private final TokenService tokenService;

    @Value("${frontend.url}")
    private String frontendUrl;

    @Value("${jwt.expiration:15}")
    private Integer jwtExpiration;

    @Value("${jwt.expiration-remember:1440}")
    private Integer jwtExpirationRemember;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException, ServletException {
        OAuth2AuthenticationToken oauthToken = (OAuth2AuthenticationToken) authentication;
        String registrationId = oauthToken.getAuthorizedClientRegistrationId();
        OAuth2User oauth2User = oauthToken.getPrincipal();

        UserEntity user = socialAuthService.processUser(registrationId, oauth2User);
        RoleEntity defaultRole = user.getRoles().stream().findFirst()
                .orElseThrow(() -> new RuntimeException("User has no roles assigned"));

        String accessToken = tokenService.generateAccessToken(user, defaultRole);
        String refreshToken = tokenService.generateRefreshToken(user);

        CookieUtils.setCookie(response, CookieUtils.ACCESS_TOKEN_COOKIE, accessToken, jwtExpiration * 60);
        CookieUtils.setCookie(response, CookieUtils.REFRESH_TOKEN_COOKIE, refreshToken, jwtExpirationRemember * 60);

        String targetUrl = UriComponentsBuilder.fromUriString(frontendUrl)
                .build().toUriString();

        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}
