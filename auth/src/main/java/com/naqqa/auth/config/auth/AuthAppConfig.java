package com.naqqa.auth.config.auth;

import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.repository.RoleRepository;
import com.naqqa.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Collections;
import java.util.Optional;

@Configuration
@RequiredArgsConstructor
public class AuthAppConfig {

    @Value("${user.config.email}")
    private String user_email;

    @Value("${user.config.password}")
    private String user_password;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

    @Bean
    public PasswordEncoder adminPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @Order(2)
    public ApplicationRunner applicationRunner() {
        PasswordEncoder encoder = adminPasswordEncoder();

        return args -> {
            final String ADMIN_ROLE_NAME = "ADMIN";

            if (userRepository.findAll().isEmpty()) {
                Optional<RoleEntity> adminRoleOpt = roleRepository.findByName(ADMIN_ROLE_NAME);

                if (adminRoleOpt.isEmpty()) {
                    throw new IllegalStateException("Cannot create default ADMIN user: ADMIN role entity is missing.");
                }

                RoleEntity adminRole = roleRepository.findByName(ADMIN_ROLE_NAME)
                        .orElseGet(() -> {
                            RoleEntity newRole = new RoleEntity();
                            newRole.setName(ADMIN_ROLE_NAME);

                            return roleRepository.save(newRole);
                        });

                UserEntity admin = UserEntity.builder()
                        .fullName("APP ADMIN")
                        .email(user_email)
                        .password(encoder.encode(user_password))
                        .roles(Collections.singleton(adminRole))
                        .lastRole(adminRole)
                        .build();
                userRepository.save(admin);
            }
        };
    }
}