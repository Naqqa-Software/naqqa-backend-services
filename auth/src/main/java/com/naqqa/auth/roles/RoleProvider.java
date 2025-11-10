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
}
