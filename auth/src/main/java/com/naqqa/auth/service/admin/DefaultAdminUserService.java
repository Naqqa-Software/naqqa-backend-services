package com.naqqa.auth.service.admin;

import com.naqqa.auth.dto.admin.AdminUserResponse;
import com.naqqa.auth.dto.admin.CreateUserRequest;
import com.naqqa.auth.dto.admin.UserRoleUpdateRequest;
import com.naqqa.auth.dto.admin.UserSubRoleUpdateRequest;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.entity.authorities.AuthorityEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.entity.authorities.SubRoleEntity;
import com.naqqa.auth.exceptions.auth.EmailInUseException;
import com.naqqa.auth.repository.RoleRepository;
import com.naqqa.auth.repository.SubRoleRepository;
import com.naqqa.auth.repository.UserRepository;
import com.naqqa.auth.roles.RoleProvider;
import com.naqqa.auth.service.security.AuthAudit;
import com.naqqa.auth.service.authorities.RbacGuard;
import com.naqqa.auth.service.security.AuthSessionIssuer;
import com.naqqa.auth.service.security.PasswordService;
import com.naqqa.auth.service.security.TokenService;
import com.naqqa.auth.sso.admin.SsoAdminAuthorities;
import com.naqqa.auth.sso.flow.AccountErasureService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class DefaultAdminUserService implements AdminUserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final SubRoleRepository subRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final RoleProvider roleProvider;
    private final ObjectProvider<AccountErasureService> erasure;
    private final ObjectProvider<AuthSessionIssuer> sessions;
    private final PasswordService passwordService;
    private final AuthAudit rbacAudit;

    @Override
    public AdminUserResponse createUser(CreateUserRequest request) {
        if (userRepository.emailTaken(request.getEmail())) {
            throw new EmailInUseException();
        }

        RoleEntity role;
        if (request.getRoleName() != null && !request.getRoleName().isEmpty()) {
            role = findRole(request.getRoleName());
        } else {
            role = roleRepository.findByName(roleProvider.getDefaultRole())
                    .orElseThrow(() -> new IllegalStateException("Default role 'USER' not found in database."));
        }
        SsoAdminAuthorities.requirePlatformFor(authorities(role));
        RbacGuard.requireHeld(authorities(role));
        passwordService.enforcePolicy(request.getPassword(), Stream.of(request.getEmail(), request.getFullName())
                .filter(v -> v != null && !v.isBlank()).toList());

        UserEntity user = UserEntity.builder()
                .email(UserRepository.normalizeEmail(request.getEmail()))
                .password(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .enabled(true)
                .roles(Collections.singleton(role))
                .build();

        userRepository.save(user);
        Map<String, String> attrs = new LinkedHashMap<>(AuthAudit.change("rolesBefore", Set.of(), "rolesAfter", Set.of(role.getName())));
        attrs.put("authoritiesAfter", AuthAudit.join(TokenService.authoritiesOf(user)));
        rbacAudit.record("rbac.user.create", String.valueOf(user.getId()), attrs);

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
        UserEntity user = roleTarget(userId);
        RoleEntity role = findRole(roleName);
        SsoAdminAuthorities.requirePlatformFor(authorities(role));
        RbacGuard.requireHeld(authorities(role));
        Snapshot before = snapshot(user);
        userRepository.addReference(user.getId(), "roles", role);
        user = findUser(userId);
        auditRoles(user, before, "add");
        return mapToResponse(user);
    }

    @Override
    public AdminUserResponse removeRole(Long userId, String roleName) {
        UserEntity user = roleTarget(userId);
        RoleEntity role = findRole(roleName);
        RbacGuard.requireHeld(authorities(role));
        Snapshot before = snapshot(user);
        userRepository.removeReference(user.getId(), "roles", role);
        user = findUser(userId);
        auditRoles(user, before, "remove");
        return mapToResponse(user);
    }

    @Override
    public AdminUserResponse replaceRoles(Long userId, UserRoleUpdateRequest request) {
        UserEntity user = roleTarget(userId);

        Set<RoleEntity> newRoles = request.getRoleNames().stream()
                .map(this::findRole)
                .collect(Collectors.toSet());
        newRoles.forEach(r -> SsoAdminAuthorities.requirePlatformFor(authorities(r)));
        changedRoles(user.getRoles(), newRoles).forEach(r -> RbacGuard.requireHeld(authorities(r)));

        Snapshot before = snapshot(user);
        user.setRoles(newRoles);
        userRepository.updateFields(user.getId(), Collections.singletonMap("roles", user.getRoles()));
        auditRoles(user, before, "replace");

        return mapToResponse(user);
    }

    @Override
    public AdminUserResponse addSubRole(Long userId, String subRoleName) {
        UserEntity user = roleTarget(userId);
        SubRoleEntity subRole = findSubRole(subRoleName);
        SsoAdminAuthorities.requirePlatformFor(authorities(subRole));
        RbacGuard.requireHeld(authorities(subRole));

        Snapshot before = snapshot(user);
        userRepository.addReference(user.getId(), "subRoles", subRole);
        user = findUser(userId);
        auditRoles(user, before, "add_sub_role");

        return mapToResponse(user);
    }

    @Override
    public AdminUserResponse removeSubRole(Long userId, String subRoleName) {
        UserEntity user = roleTarget(userId);
        SubRoleEntity subRole = findSubRole(subRoleName);
        RbacGuard.requireHeld(authorities(subRole));

        Snapshot before = snapshot(user);
        userRepository.removeReference(user.getId(), "subRoles", subRole);
        user = findUser(userId);
        auditRoles(user, before, "remove_sub_role");

        return mapToResponse(user);
    }

    @Override
    public AdminUserResponse replaceSubRoles(Long userId, UserSubRoleUpdateRequest request) {
        UserEntity user = roleTarget(userId);

        Set<SubRoleEntity> newSubRoles = request.getSubRoleNames().stream()
                .map(this::findSubRole)
                .collect(Collectors.toSet());
        newSubRoles.forEach(r -> SsoAdminAuthorities.requirePlatformFor(authorities(r)));
        changedRoles(user.getSubRoles(), newSubRoles).forEach(r -> RbacGuard.requireHeld(authorities(r)));

        Snapshot before = snapshot(user);
        user.setSubRoles(newSubRoles);
        userRepository.updateFields(user.getId(), Collections.singletonMap("subRoles", user.getSubRoles()));
        auditRoles(user, before, "replace_sub_roles");

        return mapToResponse(user);
    }

    @Override
    public AdminUserResponse disableUser(Long userId) {
        UserEntity user = protectedTarget(userId);
        user.setEnabled(false);
        userRepository.updateFields(user.getId(), Collections.singletonMap("enabled", false));
        AuthSessionIssuer issuer = sessions.getIfAvailable();
        if (issuer != null) {
            issuer.revokeAllForUser(user, "admin_disabled");
        }
        rbacAudit.record("rbac.user.disable", String.valueOf(user.getId()), Map.of("enabled", "false"));
        return mapToResponse(user);
    }

    @Override
    public AdminUserResponse enableUser(Long userId) {
        UserEntity user = protectedTarget(userId);
        user.setEnabled(true);
        userRepository.updateFields(user.getId(), Collections.singletonMap("enabled", true));
        rbacAudit.record("rbac.user.enable", String.valueOf(user.getId()), Map.of("enabled", "true"));
        return mapToResponse(user);
    }


    @Override
    public void deleteUser(Long userId) {
        UserEntity existing = userRepository.findById(userId).orElse(null);
        if (existing != null) {
            SsoAdminAuthorities.requirePlatformFor(TokenService.authoritiesOf(existing));
        }
        Map<String, String> attrs = existing == null ? Map.of() : AuthAudit.change("rolesBefore", roleNames(existing.getRoles()),
                "rolesAfter", Set.of());
        AccountErasureService service = erasure.getIfAvailable();
        if (service != null) {
            service.erase(userId, AccountErasureService.ADMIN);
            rbacAudit.record("rbac.user.delete", String.valueOf(userId), attrs);
            return;
        }
        userRepository.deleteById(userId);
        rbacAudit.record("rbac.user.delete", String.valueOf(userId), attrs);
    }

    private record Snapshot(Set<String> roles, Set<String> subRoles, Set<String> authorities) {
    }

    private static Snapshot snapshot(UserEntity user) {
        return new Snapshot(roleNames(user.getRoles()), subRoleNames(user.getSubRoles()), TokenService.authoritiesOf(user));
    }

    private void auditRoles(UserEntity user, Snapshot before, String op) {
        Snapshot after = snapshot(user);
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("op", op);
        attrs.put("rolesBefore", AuthAudit.join(before.roles()));
        attrs.put("rolesAfter", AuthAudit.join(after.roles()));
        attrs.put("subRolesBefore", AuthAudit.join(before.subRoles()));
        attrs.put("subRolesAfter", AuthAudit.join(after.subRoles()));
        Set<String> added = new TreeSet<>(after.authorities());
        added.removeAll(before.authorities());
        Set<String> removed = new TreeSet<>(before.authorities());
        removed.removeAll(after.authorities());
        attrs.put("authoritiesAdded", AuthAudit.join(added));
        attrs.put("authoritiesRemoved", AuthAudit.join(removed));
        attrs.put("authoritiesBefore", AuthAudit.join(before.authorities()));
        attrs.put("authoritiesAfter", AuthAudit.join(after.authorities()));
        rbacAudit.record("rbac.user.roles", String.valueOf(user.getId()), attrs);
    }

    private static <T> Set<T> changedRoles(Set<T> before, Set<T> after) {
        Set<T> out = new HashSet<>(before == null ? Set.of() : before);
        out.addAll(after);
        Set<T> kept = new HashSet<>(before == null ? Set.of() : before);
        kept.retainAll(after);
        out.removeAll(kept);
        return out;
    }

    private static Set<String> roleNames(Set<RoleEntity> roles) {
        return roles == null ? Set.of() : roles.stream().filter(Objects::nonNull).map(RoleEntity::getName).collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> subRoleNames(Set<SubRoleEntity> roles) {
        return roles == null ? Set.of() : roles.stream().filter(Objects::nonNull).map(SubRoleEntity::getName).collect(Collectors.toCollection(TreeSet::new));
    }

    private UserEntity findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    private UserEntity protectedTarget(Long userId) {
        UserEntity user = findUser(userId);
        SsoAdminAuthorities.requirePlatformFor(TokenService.authoritiesOf(user));
        return user;
    }

    private UserEntity roleTarget(Long userId) {
        RbacGuard.requireNotSelf(userId);
        return protectedTarget(userId);
    }

    private static Set<String> authorities(RoleEntity role) {
        return names(role.getAuthorities());
    }

    private static Set<String> authorities(SubRoleEntity role) {
        return names(role.getAuthorities());
    }

    private static Set<String> names(Set<AuthorityEntity> authorities) {
        return authorities == null ? Set.of() : authorities.stream().filter(Objects::nonNull).map(AuthorityEntity::getName)
                .collect(Collectors.toSet());
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