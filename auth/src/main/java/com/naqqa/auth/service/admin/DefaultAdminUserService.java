package com.naqqa.auth.service.admin;

import com.naqqa.auth.dto.admin.AdminUserResponse;
import com.naqqa.auth.dto.admin.CreateUserRequest;
import com.naqqa.auth.dto.admin.UserRoleUpdateRequest;
import com.naqqa.auth.dto.admin.UserSubRoleUpdateRequest;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.entity.authorities.SubRoleEntity;
import com.naqqa.auth.exceptions.auth.EmailInUseException;
import com.naqqa.auth.repository.RoleRepository;
import com.naqqa.auth.repository.SubRoleRepository;
import com.naqqa.auth.repository.UserRepository;
import com.naqqa.auth.roles.RoleProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DefaultAdminUserService implements AdminUserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final SubRoleRepository subRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final RoleProvider roleProvider;

    @Override
    public AdminUserResponse createUser(CreateUserRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new EmailInUseException();
        }

        RoleEntity role;
        if (request.getRoleName() != null && !request.getRoleName().isEmpty()) {
            role = findRole(request.getRoleName());
        } else {
            role = roleRepository.findByName(roleProvider.getDefaultRole())
                    .orElseThrow(() -> new IllegalStateException("Default role 'USER' not found in database."));
        }

        UserEntity user = UserEntity.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .enabled(true)
                .roles(Collections.singleton(role))
                .lastRole(role)
                .build();

        userRepository.save(user);

        return mapToResponse(user);
    }

    @Override
    public List<AdminUserResponse> getAllUsers() {
        return userRepository.findAll().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    public AdminUserResponse getUser(Long userId) {
        return mapToResponse(findUser(userId));
    }

    @Override
    public AdminUserResponse addRole(Long userId, String roleName) {
        UserEntity user = findUser(userId);
        RoleEntity role = findRole(roleName);
        user.getRoles().add(role);
        userRepository.save(user);
        return mapToResponse(user);
    }

    @Override
    public AdminUserResponse removeRole(Long userId, String roleName) {
        UserEntity user = findUser(userId);
        RoleEntity role = findRole(roleName);
        user.getRoles().remove(role);
        userRepository.save(user);
        return mapToResponse(user);
    }

    @Override
    public AdminUserResponse replaceRoles(Long userId, UserRoleUpdateRequest request) {
        UserEntity user = findUser(userId);

        Set<RoleEntity> newRoles = request.getRoleNames().stream()
                .map(this::findRole)
                .collect(Collectors.toSet());

        user.setRoles(newRoles);
        userRepository.save(user);

        return mapToResponse(user);
    }

    @Override
    public AdminUserResponse addSubRole(Long userId, String subRoleName) {
        UserEntity user = findUser(userId);
        SubRoleEntity subRole = findSubRole(subRoleName);

        user.getSubRoles().add(subRole);
        userRepository.save(user);

        return mapToResponse(user);
    }

    @Override
    public AdminUserResponse removeSubRole(Long userId, String subRoleName) {
        UserEntity user = findUser(userId);
        SubRoleEntity subRole = findSubRole(subRoleName);

        user.getSubRoles().remove(subRole);
        userRepository.save(user);

        return mapToResponse(user);
    }

    @Override
    public AdminUserResponse replaceSubRoles(Long userId, UserSubRoleUpdateRequest request) {
        UserEntity user = findUser(userId);

        Set<SubRoleEntity> newSubRoles = request.getSubRoleNames().stream()
                .map(this::findSubRole)
                .collect(Collectors.toSet());

        user.setSubRoles(newSubRoles);
        userRepository.save(user);

        return mapToResponse(user);
    }

    @Override
    public AdminUserResponse disableUser(Long userId) {
        UserEntity user = findUser(userId);
        user.setEnabled(false);
        userRepository.save(user);
        return mapToResponse(user);
    }

    @Override
    public AdminUserResponse enableUser(Long userId) {
        UserEntity user = findUser(userId);
        user.setEnabled(true);
        userRepository.save(user);
        return mapToResponse(user);
    }


    @Override
    public void deleteUser(Long userId) {
        userRepository.deleteById(userId);
    }

    private UserEntity findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    private RoleEntity findRole(String name) {
        return roleRepository.findByName(name)
                .orElseThrow(() -> new RuntimeException("Role not found: " + name));
    }

    private AdminUserResponse mapToResponse(UserEntity user) {
        return AdminUserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .enabled(user.isEnabled())
                .roles(
                        user.getRoles().stream()
                                .map(RoleEntity::getName)
                                .collect(Collectors.toSet())
                )
                .subRoles(
                        user.getSubRoles().stream()
                                .map(SubRoleEntity::getName)
                                .collect(Collectors.toSet())
                )
                .build();
    }

    private SubRoleEntity findSubRole(String name) {
        return subRoleRepository.findByName(name)
                .orElseThrow(() -> new RuntimeException("SubRole not found: " + name));
    }
}