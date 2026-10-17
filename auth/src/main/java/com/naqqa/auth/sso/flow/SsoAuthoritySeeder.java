package com.naqqa.auth.sso.flow;

import com.naqqa.auth.config.authorities.AuthoritySeeder;
import com.naqqa.auth.entity.authorities.AuthorityEntity;
import com.naqqa.auth.repository.AuthorityRepository;
import com.naqqa.auth.sso.admin.SsoAdminAuthorities;
import com.naqqa.auth.sso.web.ApiTokenAdminController;
import com.naqqa.auth.sso.web.AuditAdminController;
import com.naqqa.auth.sso.web.ImpersonationController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class SsoAuthoritySeeder implements AuthoritySeeder {

    public static final List<String> AUTHORITIES = List.of(ImpersonationController.AUTHORITY, ApiTokenAdminController.AUTHORITY,
            AuditAdminController.AUTHORITY, AuditAdminController.EXPORT_AUTHORITY, SsoAdminAuthorities.TENANTS, SsoAdminAuthorities.CLIENTS,
            SsoAdminAuthorities.KEYS, SsoAdminAuthorities.TOKENS, SsoAdminAuthorities.USERS);

    @Override
    public void seedAuthorities(AuthorityRepository authorityRepository) {
        Set<String> existing = authorityRepository.findAll().stream().map(AuthorityEntity::getName).collect(Collectors.toSet());
        for (String name : AUTHORITIES) {
            if (!existing.contains(name)) {
                AuthorityEntity entity = new AuthorityEntity();
                entity.setName(name);
                authorityRepository.save(entity);
            }
        }
    }
}
