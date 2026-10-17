package com.naqqa.auth.service.authorities;

import com.naqqa.auth.dto.authorities.SubRoleRequestDto;
import com.naqqa.auth.dto.authorities.SubRoleResponseDto;
import com.naqqa.auth.entity.authorities.AuthorityEntity;
import com.naqqa.auth.entity.authorities.SubRoleEntity;
import com.naqqa.auth.exceptions.authorities.*;
import com.naqqa.auth.repository.AuthorityRepository;
import com.naqqa.auth.repository.SubRoleRepository;
import com.naqqa.auth.service.security.AuthAudit;
import com.naqqa.auth.sso.admin.SsoAdminAuthorities;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@AllArgsConstructor
@Service
public class SubRoleService {

    private final SubRoleRepository subRoleRepository;
    private final AuthorityRepository authorityRepository;
    private final AuthAudit rbacAudit;

    // --- Helper: validate and fetch authorities ---
    private Set<AuthorityEntity> getOrCreateAuthorities(Set<String> authorityNames) {
        List<AuthorityEntity> existingAuthorities =
                authorityRepository.findAllByNameIn(authorityNames);

        Set<String> existingNames = existingAuthorities.stream()
                .map(AuthorityEntity::getName)
                .collect(Collectors.toSet());

        // Find which authorities do NOT exist
        Set<String> missingNames = authorityNames.stream()
                .filter(name -> !existingNames.contains(name))
                .collect(Collectors.toSet());

        // Create missing authorities
        for (String name : missingNames) {
            AuthorityEntity newAuth = new AuthorityEntity();
            newAuth.setName(name);
            authorityRepository.save(newAuth);
            existingAuthorities.add(newAuth);
        }

        return new HashSet<>(existingAuthorities);
    }


    // --- Create ---
    public SubRoleResponseDto createSubRole(SubRoleRequestDto dto) {
        if (subRoleRepository.findByName(dto.getName()).isPresent()) {
            throw new RoleAlreadyExistsException();
        }

        SsoAdminAuthorities.requirePlatformFor(dto.getAuthorityNames());
        RbacGuard.requireHeld(dto.getAuthorityNames());
        Set<AuthorityEntity> authorities = getOrCreateAuthorities(dto.getAuthorityNames());

        SubRoleEntity newRole = new SubRoleEntity();
        newRole.setName(dto.getName().toUpperCase()); // convert name to uppercase
        newRole.setAuthorities(authorities);

        SubRoleEntity savedRole = subRoleRepository.save(newRole);
        rbacAudit.record("rbac.sub_role.create", "role:" + savedRole.getName(), AuthAudit.change("authoritiesBefore", Set.of(),
                "authoritiesAfter", authorityNames(savedRole.getAuthorities())));
        return toResponseDto(savedRole);
    }

    // --- Update ---
    public SubRoleResponseDto updateSubRole(Long subRoleId, SubRoleRequestDto dto) {
        SubRoleEntity role = subRoleRepository.findById(subRoleId)
                .orElseThrow(RoleNotFoundException::new);
        SsoAdminAuthorities.requirePlatformFor(authorityNames(role.getAuthorities()));
        SsoAdminAuthorities.requirePlatformFor(dto.getAuthorityNames());
        Set<String> before = authorityNames(role.getAuthorities());
        RbacGuard.requireChangeHeld(before, dto.getAuthorityNames());
        String previousName = role.getName();

        if (!role.getName().equalsIgnoreCase(dto.getName())) {
            if (subRoleRepository.findByName(dto.getName()).isPresent()) {
                throw new RoleAlreadyExistsException();
            }
            role.setName(dto.getName().toUpperCase());
        }

        Set<AuthorityEntity> authorities = getOrCreateAuthorities(dto.getAuthorityNames());
        role.setAuthorities(authorities);
        Map<String, String> attrs = new LinkedHashMap<>(AuthAudit.change("authoritiesBefore", before, "authoritiesAfter",
                authorityNames(authorities)));
        attrs.put("nameBefore", previousName);

        SubRoleEntity updatedSubRole = subRoleRepository.save(role);
        rbacAudit.record("rbac.sub_role.update", "role:" + updatedSubRole.getName(), attrs);
        return toResponseDto(updatedSubRole);
    }

    // --- Find all roles ---
    public List<SubRoleResponseDto> findAllSubRolesWithAuthorities() {
        return subRoleRepository.findAll().stream()
                .map(this::toResponseDto)
                .collect(Collectors.toList());
    }

    // --- Find role by ID ---
    public SubRoleResponseDto findSubRoleById(Long subRoleId) {
        SubRoleEntity subRole = subRoleRepository.findById(subRoleId)
                .orElseThrow(RoleNotFoundException::new);
        return toResponseDto(subRole);
    }

    // --- Delete ---
    public void deleteSubRole(Long subRoleId) {
        SubRoleEntity subRole = subRoleRepository.findById(subRoleId)
                .orElseThrow(RoleNotFoundException::new);
        SsoAdminAuthorities.requirePlatformFor(authorityNames(subRole.getAuthorities()));
        RbacGuard.requireHeld(authorityNames(subRole.getAuthorities()));
        subRoleRepository.delete(subRole);
        rbacAudit.record("rbac.sub_role.delete", "role:" + subRole.getName(), AuthAudit.change("authoritiesBefore", authorityNames(subRole.getAuthorities()),
                "authoritiesAfter", Set.of()));
    }

    // --- Convert SubRoleEntity to SubRoleResponseDto ---
    public SubRoleResponseDto toResponseDto(SubRoleEntity role) {
        if (role == null) return null;

        Set<String> authorityNames = role.getAuthorities()
                .stream()
                .map(AuthorityEntity::getName)
                .collect(Collectors.toSet());

        return SubRoleResponseDto.builder()
                .id(role.getId())
                .name(role.getName())
                .authorityNames(authorityNames)
                .build();
    }

    private static Set<String> authorityNames(Set<AuthorityEntity> authorities) {
        return authorities == null ? Set.of() : authorities.stream().filter(Objects::nonNull).map(AuthorityEntity::getName)
                .collect(Collectors.toSet());
    }
}
