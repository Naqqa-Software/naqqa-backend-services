package com.naqqa.auth.config.authorities;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DefaultAuthorityProvider implements IAuthorityProvider {

    /**
     * Default list of domain resources for core user management and roles.
     */
    @Override
    public List<String> getProtectedResources() {
        return List.of(
                "sub_roles",
                "roles",
                "users"
        );
    }

    /**
     * Standard CRUD and management actions.
     */
    @Override
    public List<String> getBaseActions() {
        return List.of(
                "create",
                "read",
                "update",
                "delete"
        );
    }

    // Resource-specific extra actions
    @Override
    public List<String> getExtraActionsForResource(String resource) {
        return switch (resource) {
            case "users" -> List.of("enable", "disable");
            default -> List.of();
        };
    }
}