package com.naqqa.auth.config.auth;

import com.naqqa.auth.config.authorities.AuthorityRegistry;
import com.naqqa.auth.entity.authorities.AuthorityEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.repository.AuthorityRepository;
import com.naqqa.auth.repository.RoleRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Configuration
public class AuthInitDataLoader {

    private static final String ADMIN_ROLE_NAME = "ADMIN";

    @Bean
    @Order(1)
    public CommandLineRunner initAuthoritiesAndAdminRole(
            AuthorityRegistry authorityRegistry,
            AuthorityRepository authorityRepository,
            RoleRepository roleRepository) {

        return args -> {
            Set<String> allAuthorityNames = authorityRegistry.getAllAvailableAuthorities();
            Set<String> existingAuthorityNames = authorityRepository.findAll().stream()
                    .map(AuthorityEntity::getName)
                    .collect(Collectors.toSet());

            Set<String> authoritiesToSave = allAuthorityNames.stream()
                    .filter(auth -> !existingAuthorityNames.contains(auth))
                    .collect(Collectors.toSet());

            if (!authoritiesToSave.isEmpty()) {
                List<AuthorityEntity> newAuthorities = authoritiesToSave.stream()
                        .map(name -> {
                            AuthorityEntity entity = new AuthorityEntity();
                            entity.setName(name);
                            return entity;
                        })
                        .collect(Collectors.toList());
                authorityRepository.saveAll(newAuthorities);
                System.out.println("Seeded " + newAuthorities.size() + " new authorities.");
            }

            // --- CORRECTED ROLE CREATION LOGIC ---
            RoleEntity adminRole = roleRepository.findByName(ADMIN_ROLE_NAME)
                    .orElseGet(() -> {
                        RoleEntity newRole = new RoleEntity();
                        newRole.setName(ADMIN_ROLE_NAME);
                        newRole.setAuthorities(new HashSet<>());

                        return roleRepository.save(newRole);
                    });
            // --- END CORRECTED LOGIC ---


            List<AuthorityEntity> allAuthorities = authorityRepository.findAll();

            if (adminRole.getAuthorities().size() != allAuthorities.size()) {

                adminRole.setAuthorities(new HashSet<>(allAuthorities));
                roleRepository.save(adminRole);

                System.out.println("ADMIN role successfully granted ALL (" + allAuthorities.size() + ") authorities.");
            } else {
                System.out.println("ADMIN role already has full access.");
            }
        };
    }
}