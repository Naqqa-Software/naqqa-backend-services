package com.naqqa.auth.roles;

import java.util.Collection;
import java.util.List;

public class DefaultRoleProvider implements RoleProvider {
    @Override
    public Collection<String> getRoles() {
        return List.of("USER", "ADMIN");
    }

    @Override
    public String getDefaultRole() {
        return "USER";
    }

    @Override
    public String getFallbackRole(com.naqqa.auth.entity.auth.UserEntity user, String clientType) {
        return getDefaultRole();
    }

    @Override
    public boolean isRoleAllowed(String clientType, String roleName) {
        return true;
    }
}
