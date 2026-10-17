package com.naqqa.auth.config;

import com.naqqa.auth.controller.AdminUserController;
import com.naqqa.auth.controller.RolesController;
import com.naqqa.auth.controller.SubRolesController;
import com.naqqa.auth.security.AccessSessionGuard;
import com.naqqa.auth.security.AccessTokenAuthenticationFilter;
import com.naqqa.auth.security.AuthoritiesCustomizer;
import com.naqqa.auth.security.JsonSecurityHandlers;
import com.naqqa.auth.security.MalformedRequestFilter;
import com.naqqa.auth.service.security.AccessTokenVerifier;
import com.naqqa.auth.service.security.NaqqaPasswordEncoder;
import com.naqqa.auth.service.security.PasswordService;
import com.naqqa.auth.sso.web.filter.ApiTokenAuthenticationFilter;
import com.naqqa.auth.sso.web.filter.SsoCsrfFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.filter.OrderedFormContentFilter;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class AuthSecurityConfig {

    public static final String API_CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";
    static final List<String> ADMIN_USER_ROUTES = List.of("/api/admin/users", "/api/admin/users/{userId}", "/api/admin/users/{userId}/roles",
            "/api/admin/users/{userId}/roles/{roleName}", "/api/admin/users/{userId}/disable", "/api/admin/users/{userId}/enable");
    static final List<String> ROLE_ROUTES = List.of("/api/roles", "/api/roles/authorities", "/api/roles/switch", "/api/roles/{id}");
    static final List<String> SUB_ROLE_ROUTES = List.of("/api/sub-roles", "/api/sub-roles/authorities", "/api/sub-roles/{id}");
    public static final List<String> PROTOCOL_PATHS = List.of("/oauth2/**", "/login/oauth2/code/*", "/.well-known/**", "/saml2/**", "/scim/v2/**", "/scim/v2");

    public static final String APPLE_FORM_POST_ORIGIN = "https://appleid.apple.com";
    static final String SOCIAL_CALLBACK_PREFIX = "/login/oauth2/code/";
    public static final Pattern SAML_SP_ACS = Pattern.compile("/saml2/sp/[a-z0-9][a-z0-9-]{1,62}/acs");
    static final List<String> AUTH_CHAINS = List.of("appleFormPostCallbackFilterChain", "authSecurityFilterChain", "ssoProtocolSecurityFilterChain");

    @Value("${auth.api.base-path:/api/auth}")
    private String basePath;

    @Bean
    public AccessTokenAuthenticationFilter accessTokenAuthenticationFilter(AccessTokenVerifier verifier,
                                                                           ObjectProvider<AuthoritiesCustomizer> customizers,
                                                                           ObjectProvider<AccessSessionGuard> sessionGuard) {
        return new AccessTokenAuthenticationFilter(verifier, customizers, sessionGuard);
    }

    @Bean
    public FilterRegistrationBean<AccessTokenAuthenticationFilter> accessTokenAuthenticationFilterRegistration(AccessTokenAuthenticationFilter filter) {
        FilterRegistrationBean<AccessTokenAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<MalformedRequestFilter> malformedRequestFilterRegistration(ApplicationContext context) {
        MalformedRequestFilter filter = new MalformedRequestFilter(() -> AUTH_CHAINS.stream()
                .filter(context::containsBean)
                .map(name -> context.getBean(name, SecurityFilterChain.class))
                .toList());
        FilterRegistrationBean<MalformedRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(OrderedFormContentFilter.DEFAULT_ORDER - 1);
        return registration;
    }

    @Bean
    public JsonSecurityHandlers jsonSecurityHandlers() {
        return new JsonSecurityHandlers();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain authSecurityFilterChain(HttpSecurity http,
                                                       AccessTokenAuthenticationFilter accessTokenFilter,
                                                       ObjectProvider<ApiTokenAuthenticationFilter> apiTokenFilter,
                                                       ObjectProvider<SsoCsrfFilter> csrfFilter,
                                                       JsonSecurityHandlers handlers,
                                                       ObjectProvider<AdminUserController> adminUsers,
                                                       ObjectProvider<RolesController> roles,
                                                       ObjectProvider<SubRolesController> subRoles) throws Exception {
        List<String> paths = apiPaths(basePath, adminUsers.getIfAvailable() != null, roles.getIfAvailable() != null,
                subRoles.getIfAvailable() != null);
        http.securityMatcher(paths.toArray(new String[0]));
        common(http, accessTokenFilter, apiTokenFilter, handlers);
        SsoCsrfFilter csrf = csrfFilter.getIfAvailable();
        if (csrf != null) {
            http.addFilterAfter(csrf, AccessTokenAuthenticationFilter.class);
        }
        return http.build();
    }

    public static List<String> apiPaths(String basePath, boolean adminUsers, boolean roles, boolean subRoles) {
        String base = basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
        List<String> paths = new ArrayList<>(List.of("/api/auth/**", "/api/custom-auth/**", "/api/sso/**", base + "/**"));
        if (adminUsers) {
            paths.addAll(ADMIN_USER_ROUTES);
        }
        if (roles) {
            paths.addAll(ROLE_ROUTES);
        }
        if (subRoles) {
            paths.addAll(SUB_ROLE_ROUTES);
        }
        return paths;
    }

    @Bean
    @Order(0)
    public SecurityFilterChain appleFormPostCallbackFilterChain(HttpSecurity http,
                                                                AccessTokenAuthenticationFilter accessTokenFilter,
                                                                ObjectProvider<ApiTokenAuthenticationFilter> apiTokenFilter,
                                                                JsonSecurityHandlers handlers) throws Exception {
        http.securityMatcher(request -> isAppleFormPostCallback(request) || isSamlSpAcs(request));
        common(http, accessTokenFilter, apiTokenFilter, handlers);
        http.cors(cors -> cors.disable());
        return http.build();
    }

    static boolean isAppleFormPostCallback(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod()) || !APPLE_FORM_POST_ORIGIN.equals(request.getHeader("Origin"))) {
            return false;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith(SOCIAL_CALLBACK_PREFIX) && path.indexOf('/', SOCIAL_CALLBACK_PREFIX.length()) < 0
                && path.length() > SOCIAL_CALLBACK_PREFIX.length();
    }

    static boolean isSamlSpAcs(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return SAML_SP_ACS.matcher(path).matches();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain ssoProtocolSecurityFilterChain(HttpSecurity http,
                                                              AccessTokenAuthenticationFilter accessTokenFilter,
                                                              ObjectProvider<ApiTokenAuthenticationFilter> apiTokenFilter,
                                                              JsonSecurityHandlers handlers) throws Exception {
        http.securityMatcher(PROTOCOL_PATHS.toArray(new String[0]));
        common(http, accessTokenFilter, apiTokenFilter, handlers);
        return http.build();
    }

    private void common(HttpSecurity http, AccessTokenAuthenticationFilter accessTokenFilter,
                        ObjectProvider<ApiTokenAuthenticationFilter> apiTokenFilter, JsonSecurityHandlers handlers) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .logout(logout -> logout.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(API_CSP))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .frameOptions(frame -> frame.deny()))
                .addFilterBefore(accessTokenFilter, AnonymousAuthenticationFilter.class);
        ApiTokenAuthenticationFilter apiTokens = apiTokenFilter.getIfAvailable();
        if (apiTokens != null) {
            http.addFilterBefore(apiTokens, AccessTokenAuthenticationFilter.class);
        }
    }

    @Bean
    @ConditionalOnMissingBean(PasswordEncoder.class)
    public PasswordEncoder passwordEncoder(PasswordService passwordService) {
        return new NaqqaPasswordEncoder(passwordService);
    }

    @Bean
    @ConditionalOnMissingBean(AuthenticationManager.class)
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
