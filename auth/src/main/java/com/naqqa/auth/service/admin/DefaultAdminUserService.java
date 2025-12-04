package com.naqqa.auth.service.admin;

import com.naqqa.auth.dto.admin.AdminUserResponse;
import com.naqqa.auth.dto.admin.UserRoleUpdateRequest;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.repository.RoleRepository;
import com.naqqa.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DefaultAdminUserService implements AdminUserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

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
                .build();
    }
}