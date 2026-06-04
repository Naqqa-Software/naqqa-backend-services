package com.naqqa.auth.roles;

import java.util.Collection;

public interface RoleProvider {
    /**
     * Returns the list of available roles for this project.
     */
    Collection<String> getRoles();

    /**
     * Returns the default role assigned to new users (usually "USER").
     */
    default String getDefaultRole() {
        return "USER";
    }

    /**
     * Returns the fallback role for a specific device type when no active role session exists.
     */
    String getFallbackRole(com.naqqa.auth.entity.auth.UserEntity user, String clientType);

    /**
     * Checks if a role is allowed to be assumed on a specific client type.
     */
    boolean isRoleAllowed(String clientType, String roleName);
}
