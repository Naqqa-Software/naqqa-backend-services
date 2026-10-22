package com.naqqa.auth.roles;

import java.util.Collection;

public interface RoleProvider {
    Collection<String> getRoles();

    default String getDefaultRole() {
        return "USER";
    }

    String getFallbackRole(com.naqqa.auth.entity.auth.UserEntity user, String clientType);

    boolean isRoleAllowed(String clientType, String roleName);
}
