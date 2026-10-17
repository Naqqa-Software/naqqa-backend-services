package com.naqqa.auth.service.authorities;

import com.naqqa.auth.config.authorities.AuthoritiesErrors;
import com.naqqa.auth.dto.authorities.RoleRequestDto;
import com.naqqa.auth.dto.authorities.RoleResponseDto;
import com.naqqa.auth.dto.authorities.SwitchRoleRequest;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.entity.auth.UserDeviceEntity;
import com.naqqa.auth.entity.authorities.AuthorityEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.exceptions.ForbiddenException;
import com.naqqa.auth.exceptions.ResourceNotFoundException;
import com.naqqa.auth.exceptions.authorities.*;
import com.naqqa.auth.repository.AuthorityRepository;
import com.naqqa.auth.repository.RoleRepository;
import com.naqqa.auth.repository.UserDeviceRepository;
import com.naqqa.auth.repository.UserRepository;
import com.naqqa.auth.roles.RoleProvider;
import com.naqqa.auth.security.AuthPrincipal;
import com.naqqa.auth.service.security.TokenService;
import com.naqqa.auth.service.security.AuthAudit;
import com.naqqa.auth.sso.admin.SsoAdminAuthorities;
import lombok.AllArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@AllArgsConstructor
@Service
public class RoleService {

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final AuthorityRepository authorityRepository;
    private final UserDeviceRepository userDeviceRepository;
    private final RoleProvider roleProvider;
    private final TokenService tokenService;
    private final AuthAudit rbacAudit;
    private final Clock clock;

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
    public RoleResponseDto createRole(RoleRequestDto dto) {
        if (roleRepository.findByName(dto.getName()).isPresent()) {
            throw new RoleAlreadyExistsException();
        }

        SsoAdminAuthorities.requirePlatformFor(dto.getAuthorityNames());
        RbacGuard.requireHeld(dto.getAuthorityNames());
        Set<AuthorityEntity> authorities = getOrCreateAuthorities(dto.getAuthorityNames());

        RoleEntity newRole = new RoleEntity();
        newRole.setName(dto.getName().toUpperCase()); // convert name to uppercase
        newRole.setAuthorities(authorities);

        RoleEntity savedRole = roleRepository.save(newRole);
        rbacAudit.record("rbac.role.create", "role:" + savedRole.getName(), AuthAudit.change("authoritiesBefore", Set.of(),
                "authoritiesAfter", authorityNames(savedRole.getAuthorities())));
        return toResponseDto(savedRole);
    }

    // --- Update ---
    public RoleResponseDto updateRole(Long roleId, RoleRequestDto dto) {
        RoleEntity role = roleRepository.findById(roleId)
                .orElseThrow(RoleNotFoundException::new);
        SsoAdminAuthorities.requirePlatformFor(authorityNames(role.getAuthorities()));
        SsoAdminAuthorities.requirePlatformFor(dto.getAuthorityNames());
        Set<String> before = authorityNames(role.getAuthorities());
        RbacGuard.requireChangeHeld(before, dto.getAuthorityNames());
        String previousName = role.getName();

        if (!role.getName().equalsIgnoreCase(dto.getName())) {
            if (roleRepository.findByName(dto.getName()).isPresent()) {
                throw new RoleAlreadyExistsException();
            }
            role.setName(dto.getName().toUpperCase());
        }

        Set<AuthorityEntity> authorities = getOrCreateAuthorities(dto.getAuthorityNames());
        role.setAuthorities(authorities);
        Map<String, String> attrs = new LinkedHashMap<>(AuthAudit.change("authoritiesBefore", before, "authoritiesAfter",
                authorityNames(authorities)));
        attrs.put("nameBefore", previousName);

        RoleEntity updatedRole = roleRepository.save(role);
        rbacAudit.record("rbac.role.update", "role:" + updatedRole.getName(), attrs);
        return toResponseDto(updatedRole);
    }

    // --- Find all roles ---
    public List<RoleResponseDto> findAllRolesWithAuthorities() {
        return roleRepository.findAll().stream()
                .map(this::toResponseDto)
                .collect(Collectors.toList());
    }

    // --- Find role by ID ---
    public RoleResponseDto findRoleById(Long roleId) {
        RoleEntity role = roleRepository.findById(roleId)
                .orElseThrow(RoleNotFoundException::new);
        return toResponseDto(role);
    }

    // --- Delete ---
    public void deleteRole(Long roleId) {
        RoleEntity role = roleRepository.findById(roleId)
                .orElseThrow(RoleNotFoundException::new);
        SsoAdminAuthorities.requirePlatformFor(authorityNames(role.getAuthorities()));
        RbacGuard.requireHeld(authorityNames(role.getAuthorities()));
        roleRepository.delete(role);
        rbacAudit.record("rbac.role.delete", "role:" + role.getName(), AuthAudit.change("authoritiesBefore", authorityNames(role.getAuthorities()),
                "authoritiesAfter", Set.of()));
    }


    public String switchRole(String email, SwitchRoleRequest request) {
        UserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException(AuthoritiesErrors.INVALID_REQUEST));
        return switchRole(user, request);
    }

    public String switchRole(Long userId, SwitchRoleRequest request) {
        UserEntity user = userId == null ? null : userRepository.findById(userId).orElse(null);
        if (user == null) {
            throw new ResourceNotFoundException(AuthoritiesErrors.INVALID_REQUEST);
        }
        return switchRole(user, request);
    }

    private String switchRole(UserEntity user, SwitchRoleRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth != null && auth.getPrincipal() instanceof AuthPrincipal caller) || !String.valueOf(user.getId()).equals(caller.getSubject())) {
            throw new ForbiddenException(AuthoritiesErrors.FORBIDDEN);
        }
        if (request == null || request.roleId() == null) {
            throw new ResourceNotFoundException(AuthoritiesErrors.ROLE_NOT_FOUND);
        }

        RoleEntity selectedRole = roleRepository.findById(request.roleId())
                .orElseThrow(() -> new ResourceNotFoundException(AuthoritiesErrors.ROLE_NOT_FOUND));

        if (!user.getRoles().contains(selectedRole)) {
            throw new ForbiddenException(AuthoritiesErrors.FORBIDDEN);
        }

        String deviceId = request.deviceId() != null && !request.deviceId().isBlank() ? request.deviceId() : "DEFAULT_DEVICE";
        String clientType = request.clientType() != null && !request.clientType().isBlank() ? request.clientType() : "DEFAULT";

        if (!roleProvider.isRoleAllowed(clientType, selectedRole.getName())) {
            throw new ForbiddenException(AuthoritiesErrors.FORBIDDEN);
        }

        Instant now = clock.instant();
        Instant expiresAt = caller.getExpiresAt();
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            throw new ForbiddenException(AuthoritiesErrors.FORBIDDEN);
        }

        UserDeviceEntity userDevice = userDeviceRepository.findByUserIdAndDeviceId(user.getId(), deviceId)
                .orElseGet(() -> UserDeviceEntity.builder()
                        .userId(user.getId())
                        .deviceId(deviceId)
                        .clientType(clientType)
                        .build());

        userDevice.setClientType(clientType);
        userDevice.setLastRole(selectedRole);
        userDeviceRepository.save(userDevice);

        String token = tokenService.generateAccessToken(user, selectedRole, new TokenService.AccessContext(caller.getSessionId(),
                caller.getAmr(), caller.getAcr(), caller.getAuthTime(), caller.getActor(), Duration.between(now, expiresAt)));
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("role", selectedRole.getName());
        attrs.put("roleBefore", caller.getClaimAsMap("role") == null ? "" : String.valueOf(caller.getClaimAsMap("role").get("name")));
        if (caller.isImpersonated()) {
            attrs.put("impersonatedBy", String.valueOf(caller.getActor().get("sub")));
        }
        rbacAudit.record("rbac.role.switch", String.valueOf(user.getId()), attrs);
        return token;
    }


    // --- Convert RoleEntity to RoleResponseDto ---
    public RoleResponseDto toResponseDto(RoleEntity role) {
        if (role == null) return null;

        Set<String> authorityNames = role.getAuthorities()
                .stream()
                .map(AuthorityEntity::getName)
                .collect(Collectors.toSet());

        return RoleResponseDto.builder()
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
