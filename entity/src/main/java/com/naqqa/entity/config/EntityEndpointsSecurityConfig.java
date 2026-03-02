package com.naqqa.entity.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class EntityEndpointsSecurityConfig {

    @Bean
    @Order(0)
    public SecurityFilterChain entitySecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/entities/**")
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt());
        return http.build();
    }
}

