package com.naqqa.auth.support;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.roles.RoleProvider;

import java.util.Collection;
import java.util.List;

public class TestRoleProvider implements RoleProvider {

    @Override
    public Collection<String> getRoles() {
        return List.of("USER", "ADMIN", "SUPPORT");
    }

    @Override
    public String getFallbackRole(UserEntity user, String clientType) {
        return "USER";
    }

    @Override
    public boolean isRoleAllowed(String clientType, String roleName) {
        return true;
    }
}
