package com.naqqa.auth.service.authorities;

import com.naqqa.auth.dto.authorities.RoleRequestDto;
import com.naqqa.auth.dto.authorities.RoleResponseDto;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.entity.authorities.AuthorityEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.exceptions.ForbiddenException;
import com.naqqa.auth.exceptions.ResourceNotFoundException;
import com.naqqa.auth.exceptions.authorities.*;
import com.naqqa.auth.repository.AuthorityRepository;
import com.naqqa.auth.repository.RoleRepository;
import com.naqqa.auth.repository.UserRepository;
import com.naqqa.auth.security.SecurityUtils;
import com.naqqa.auth.service.security.TokenService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@AllArgsConstructor
@Service
public class RoleService {

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final AuthorityRepository authorityRepository;
    private final TokenService tokenService;

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
    @Transactional
    public RoleResponseDto createRole(RoleRequestDto dto) {
        if (roleRepository.findByName(dto.getName()).isPresent()) {
            throw new RoleAlreadyExistsException();
        }

        Set<AuthorityEntity> authorities = getOrCreateAuthorities(dto.getAuthorityNames());

        RoleEntity newRole = new RoleEntity();
        newRole.setName(dto.getName().toUpperCase()); // convert name to uppercase
        newRole.setAuthorities(authorities);

        RoleEntity savedRole = roleRepository.save(newRole);
        return toResponseDto(savedRole);
    }

    // --- Update ---
    @Transactional
    public RoleResponseDto updateRole(Long roleId, RoleRequestDto dto) {
        RoleEntity role = roleRepository.findById(roleId)
                .orElseThrow(RoleNotFoundException::new);

        if (!role.getName().equalsIgnoreCase(dto.getName())) {
            if (roleRepository.findByName(dto.getName()).isPresent()) {
                throw new RoleAlreadyExistsException();
            }
            role.setName(dto.getName().toUpperCase());
        }

        Set<AuthorityEntity> authorities = getOrCreateAuthorities(dto.getAuthorityNames());
        role.setAuthorities(authorities);

        RoleEntity updatedRole = roleRepository.save(role);
        return toResponseDto(updatedRole);
    }

    // --- Find all roles ---
    @Transactional(readOnly = true)
    public List<RoleResponseDto> findAllRolesWithAuthorities() {
        return roleRepository.findAll().stream()
                .map(this::toResponseDto)
                .collect(Collectors.toList());
    }

    // --- Find role by ID ---
    @Transactional(readOnly = true)
    public RoleResponseDto findRoleById(Long roleId) {
        RoleEntity role = roleRepository.findById(roleId)
                .orElseThrow(RoleNotFoundException::new);
        return toResponseDto(role);
    }

    // --- Delete ---
    @Transactional
    public void deleteRole(Long roleId) {
        RoleEntity role = roleRepository.findById(roleId)
                .orElseThrow(RoleNotFoundException::new);
        roleRepository.delete(role);
    }


    public String switchRole(Long roleId) {
        String email = SecurityUtils.getCurrentUserEmail();
        UserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        RoleEntity selectedRole = roleRepository.findById(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found"));

        if (!user.getRoles().contains(selectedRole)) {
            throw new ForbiddenException("You are not allowed to switch to this role");
        }

        user.setLastRole(selectedRole);
        userRepository.save(user);

        return tokenService.generateToken(user);
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


}
