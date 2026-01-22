package com.naqqa.auth.controller;

import com.naqqa.auth.config.authorities.AuthorityRegistry;
import com.naqqa.auth.dto.authorities.SubRoleRequestDto;
import com.naqqa.auth.dto.authorities.SubRoleResponseDto;
import com.naqqa.auth.dto.authorities.SwitchRoleRequest;
import com.naqqa.auth.service.authorities.RoleService;
import com.naqqa.auth.service.authorities.SubRoleService;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@AllArgsConstructor
@RequestMapping("/api/sub-roles")
public class SubRolesController {

    private final AuthorityRegistry authorityRegistry;
    private final SubRoleService subRoleService;

    @GetMapping("/authorities")
    @PreAuthorize("hasAuthority('sub_roles:read')")
    public ResponseEntity<Set<String>> getAuthorityOptions() {
        return ResponseEntity.ok(authorityRegistry.getAllAvailableAuthorities());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('sub_roles:read')")
    public ResponseEntity<List<SubRoleResponseDto>> getAllRoles() {
        return ResponseEntity.ok(subRoleService.findAllSubRolesWithAuthorities());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('sub_roles:read')")
    public ResponseEntity<SubRoleResponseDto> getRoleById(@PathVariable Long id) {
        return ResponseEntity.ok(subRoleService.findSubRoleById(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('sub_roles:create')")
    public ResponseEntity<SubRoleResponseDto> createRole(@RequestBody SubRoleRequestDto roleDto) {
        return ResponseEntity.ok(subRoleService.createSubRole(roleDto));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('sub_roles:update')")
    public ResponseEntity<SubRoleResponseDto> updateRole(
            @PathVariable Long id,
            @RequestBody SubRoleRequestDto roleDto
    ) {
        return ResponseEntity.ok(subRoleService.updateSubRole(id, roleDto));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('sub_roles:delete')")
    public ResponseEntity<Void> deleteRole(@PathVariable Long id) {
        subRoleService.deleteSubRole(id);
        return ResponseEntity.noContent().build();
    }
}
