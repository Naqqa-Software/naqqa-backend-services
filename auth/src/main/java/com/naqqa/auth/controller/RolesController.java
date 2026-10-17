package com.naqqa.auth.controller;

import com.naqqa.auth.config.authorities.AuthorityRegistry;
import com.naqqa.auth.dto.authorities.RoleRequestDto;
import com.naqqa.auth.dto.authorities.RoleResponseDto;
import com.naqqa.auth.dto.authorities.SwitchRoleRequest;
import com.naqqa.auth.security.AuthPrincipal;
import com.naqqa.auth.service.authorities.RoleService;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import com.naqqa.auth.security.RequiresStepUp;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@AllArgsConstructor
@RequestMapping("/api/roles")
public class RolesController {
    private final AuthorityRegistry authorityRegistry;
    private final RoleService roleService;

    @GetMapping("/authorities")
    @PreAuthorize("hasAuthority('roles:read')")
    public ResponseEntity<Set<String>> getAuthorityOptions() {
        return ResponseEntity.ok(authorityRegistry.getAllAvailableAuthorities());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('roles:read')")
    public ResponseEntity<List<RoleResponseDto>> getAllRoles() {
        return ResponseEntity.ok(roleService.findAllRolesWithAuthorities());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('roles:read')")
    public ResponseEntity<RoleResponseDto> getRoleById(@PathVariable Long id) {
        return ResponseEntity.ok(roleService.findRoleById(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('roles:create')")
    @RequiresStepUp
    public ResponseEntity<RoleResponseDto> createRole(@RequestBody RoleRequestDto roleDto) {
        return ResponseEntity.ok(roleService.createRole(roleDto));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('roles:update')")
    @RequiresStepUp
    public ResponseEntity<RoleResponseDto> updateRole(
            @PathVariable Long id,
            @RequestBody RoleRequestDto roleDto
    ) {
        return ResponseEntity.ok(roleService.updateRole(id, roleDto));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('roles:delete')")
    @RequiresStepUp
    public ResponseEntity<Void> deleteRole(@PathVariable Long id) {
        roleService.deleteRole(id);
        return ResponseEntity.noContent().build();
    }


    @PostMapping("/switch")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<String> switchRole(
            @RequestBody SwitchRoleRequest request
    ) {
        Long userId = SecurityContextHolder.getContext().getAuthentication().getPrincipal() instanceof AuthPrincipal p ? p.getUserId() : null;
        String newToken = roleService.switchRole(userId, request);
        return ResponseEntity.ok(newToken);
    }
}
