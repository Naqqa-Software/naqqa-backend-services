package com.naqqa.auth.config.auth;

import com.naqqa.auth.config.authorities.AuthorityRegistry;
import com.naqqa.auth.config.authorities.AuthoritySeeder;
import com.naqqa.auth.config.authorities.RoleSeeder;
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
    @Order(0)
    public CommandLineRunner initCustomAuthorities(
            List<AuthoritySeeder> authoritySeeders,
            AuthorityRepository authorityRepository
    ) {
        return args -> {
            if (authoritySeeders == null || authoritySeeders.isEmpty()) {
                return;
            }

            for (AuthoritySeeder seeder : authoritySeeders) {
                seeder.seedAuthorities(authorityRepository);
            }
        };
    }


    @Bean
    @Order(1)
    public CommandLineRunner initAuthoritiesAndAdminRole(
            AuthorityRegistry authorityRegistry,
            AuthorityRepository authorityRepository,
            RoleRepository roleRepository
    ) {
        return args -> {
            /* =========================
             * 1. Seed authorities
             * ========================= */
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
                        .toList();

                authorityRepository.saveAll(newAuthorities);
                System.out.println("Seeded " + newAuthorities.size() + " new authorities.");
            }

            /* =========================
             * 2. Ensure ADMIN role exists
             * ========================= */
            RoleEntity adminRole = roleRepository.findByName(ADMIN_ROLE_NAME)
                    .orElseGet(() -> {
                        RoleEntity role = new RoleEntity();
                        role.setName(ADMIN_ROLE_NAME);
                        role.setAuthorities(new HashSet<>());
                        return roleRepository.save(role);
                    });

            /* =========================
             * 3. Merge ALL authorities into ADMIN
             * ========================= */
            Set<AuthorityEntity> allAuthorities =
                    new HashSet<>(authorityRepository.findAll());

            Set<AuthorityEntity> adminAuthorities =
                    new HashSet<>(adminRole.getAuthorities());

            boolean changed = adminAuthorities.addAll(allAuthorities);

            if (changed) {
                adminRole.setAuthorities(adminAuthorities);
                roleRepository.save(adminRole);

                System.out.println(
                        "ADMIN role updated. Total authorities: " + adminAuthorities.size()
                );
            } else {
                System.out.println("ADMIN role already has full access.");
            }
        };
    }

    @Bean
    @Order(2)
    public CommandLineRunner initRoles(
            List<RoleSeeder> roleSeeders,
            RoleRepository roleRepository,
            AuthorityRepository authorityRepository
    ) {
        return args -> {
            for (RoleSeeder seeder : roleSeeders) {
                seeder.seedRoles(roleRepository, authorityRepository);
            }
        };
    }
}