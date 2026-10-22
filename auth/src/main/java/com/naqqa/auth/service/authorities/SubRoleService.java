package com.naqqa.auth.service.authorities;

import com.naqqa.auth.dto.authorities.SubRoleRequestDto;
import com.naqqa.auth.dto.authorities.SubRoleResponseDto;
import com.naqqa.auth.entity.authorities.AuthorityEntity;
import com.naqqa.auth.entity.authorities.SubRoleEntity;
import com.naqqa.auth.exceptions.authorities.*;
import com.naqqa.auth.repository.AuthorityRepository;
import com.naqqa.auth.repository.SubRoleRepository;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@AllArgsConstructor
@Service
public class SubRoleService {

    private final SubRoleRepository subRoleRepository;
    private final AuthorityRepository authorityRepository;

    private Set<AuthorityEntity> getOrCreateAuthorities(Set<String> authorityNames) {
        List<AuthorityEntity> existingAuthorities =
                authorityRepository.findAllByNameIn(authorityNames);

        Set<String> existingNames = existingAuthorities.stream()
                .map(AuthorityEntity::getName)
                .collect(Collectors.toSet());

        Set<String> missingNames = authorityNames.stream()
                .filter(name -> !existingNames.contains(name))
                .collect(Collectors.toSet());

        for (String name : missingNames) {
            AuthorityEntity newAuth = new AuthorityEntity();
            newAuth.setName(name);
            authorityRepository.save(newAuth);
            existingAuthorities.add(newAuth);
        }

        return new HashSet<>(existingAuthorities);
    }


    @Transactional
    public SubRoleResponseDto createSubRole(SubRoleRequestDto dto) {
        if (subRoleRepository.findByName(dto.getName()).isPresent()) {
            throw new RoleAlreadyExistsException();
        }

        Set<AuthorityEntity> authorities = getOrCreateAuthorities(dto.getAuthorityNames());

        SubRoleEntity newRole = new SubRoleEntity();
        newRole.setName(dto.getName().toUpperCase());
        newRole.setAuthorities(authorities);

        SubRoleEntity savedRole = subRoleRepository.save(newRole);
        return toResponseDto(savedRole);
    }

    @Transactional
    public SubRoleResponseDto updateSubRole(Long subRoleId, SubRoleRequestDto dto) {
        SubRoleEntity role = subRoleRepository.findById(subRoleId)
                .orElseThrow(RoleNotFoundException::new);

        if (!role.getName().equalsIgnoreCase(dto.getName())) {
            if (subRoleRepository.findByName(dto.getName()).isPresent()) {
                throw new RoleAlreadyExistsException();
            }
            role.setName(dto.getName().toUpperCase());
        }

        Set<AuthorityEntity> authorities = getOrCreateAuthorities(dto.getAuthorityNames());
        role.setAuthorities(authorities);

        SubRoleEntity updatedSubRole = subRoleRepository.save(role);
        return toResponseDto(updatedSubRole);
    }

    @Transactional(readOnly = true)
    public List<SubRoleResponseDto> findAllSubRolesWithAuthorities() {
        return subRoleRepository.findAll().stream()
                .map(this::toResponseDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public SubRoleResponseDto findSubRoleById(Long subRoleId) {
        SubRoleEntity subRole = subRoleRepository.findById(subRoleId)
                .orElseThrow(RoleNotFoundException::new);
        return toResponseDto(subRole);
    }

    @Transactional
    public void deleteSubRole(Long subRoleId) {
        SubRoleEntity subRole = subRoleRepository.findById(subRoleId)
                .orElseThrow(RoleNotFoundException::new);
        subRoleRepository.delete(subRole);
    }

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
}
