package com.naqqa.auth.controller;

import com.naqqa.auth.dto.admin.UserRoleUpdateRequest;
import com.naqqa.auth.dto.admin.AdminUserResponse;
import com.naqqa.auth.service.admin.AdminUserService;
import com.naqqa.auth.service.admin.DefaultAdminUserService;
import lombok.AllArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@AllArgsConstructor
@RequestMapping("/api/admin/users")
public class AdminUserController {

   private final AdminUserService adminUserService;

    @GetMapping
    @PreAuthorize("hasAuthority('users:read')")
    public List<AdminUserResponse> getAllUsers() {
        return adminUserService.getAllUsers();
    }

    @GetMapping("/{userId}")
    @PreAuthorize("hasAuthority('users:read')")
    public AdminUserResponse getUser(@PathVariable Long userId) {
        return adminUserService.getUser(userId);
    }

    @PostMapping("/{userId}/roles/{roleName}")
    @PreAuthorize("hasAuthority('users:update')")
    public AdminUserResponse addRole(
            @PathVariable Long userId,
            @PathVariable String roleName
    ) {
        return adminUserService.addRole(userId, roleName);
    }

    @DeleteMapping("/{userId}/roles/{roleName}")
    @PreAuthorize("hasAuthority('users:update')")
    public AdminUserResponse removeRole(
            @PathVariable Long userId,
            @PathVariable String roleName
    ) {
        return adminUserService.removeRole(userId, roleName);
    }

    @PutMapping("/{userId}/roles")
    @PreAuthorize("hasAuthority('users:update')")
    public AdminUserResponse replaceRoles(
            @PathVariable Long userId,
            @RequestBody UserRoleUpdateRequest request
    ) {
        return adminUserService.replaceRoles(userId, request);
    }

    @PatchMapping("/{userId}/disable")
    @PreAuthorize("hasAuthority('users:disable')")
    public AdminUserResponse disableUser(@PathVariable Long userId) {
        return adminUserService.disableUser(userId);
    }

    @PatchMapping("/{userId}/enable")
    @PreAuthorize("hasAuthority('users:enable')")
    public AdminUserResponse enableUser(@PathVariable Long userId) {
        return adminUserService.enableUser(userId);
    }

    @DeleteMapping("/{userId}")
    @PreAuthorize("hasAuthority('users:delete')")
    public void deleteUser(@PathVariable Long userId) {
        adminUserService.deleteUser(userId);
    }
}
