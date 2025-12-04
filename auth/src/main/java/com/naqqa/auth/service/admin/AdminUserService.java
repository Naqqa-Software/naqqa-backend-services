package com.naqqa.auth.service.admin;

import com.naqqa.auth.dto.admin.AdminUserResponse;
import com.naqqa.auth.dto.admin.UserRoleUpdateRequest;

import java.util.List;

public interface AdminUserService {

    List<AdminUserResponse> getAllUsers();

    AdminUserResponse getUser(Long userId);

    AdminUserResponse addRole(Long userId, String roleName);

    AdminUserResponse removeRole(Long userId, String roleName);

    AdminUserResponse replaceRoles(Long userId, UserRoleUpdateRequest request);

    AdminUserResponse disableUser(Long userId);

    AdminUserResponse enableUser(Long userId);

    void deleteUser(Long userId);
}
