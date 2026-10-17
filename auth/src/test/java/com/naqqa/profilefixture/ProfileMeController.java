package com.naqqa.profilefixture;

import com.naqqa.auth.security.AccessTokenAuthenticationFilter;
import com.naqqa.auth.security.AuthPrincipal;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Configuration
@Import(ProfileMeController.Api.class)
public class ProfileMeController {

    public record Me(String sub, List<String> roles) {
    }

    @Bean
    @Order(5)
    public SecurityFilterChain profileFixtureChain(HttpSecurity http, AccessTokenAuthenticationFilter accessTokenFilter) throws Exception {
        return http.securityMatcher("/api/profile/**")
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .addFilterBefore(accessTokenFilter, AnonymousAuthenticationFilter.class)
                .build();
    }

    @RestController
    @RequestMapping("/api/profile")
    public static class Api {

        @GetMapping("/me")
        public Me me(@AuthenticationPrincipal AuthPrincipal principal) {
            return new Me(principal == null ? null : principal.getSubject(), List.of("ADMIN"));
        }
    }
}
