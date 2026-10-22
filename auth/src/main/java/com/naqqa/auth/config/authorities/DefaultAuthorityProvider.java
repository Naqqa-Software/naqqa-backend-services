package com.naqqa.auth.config.authorities;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DefaultAuthorityProvider implements IAuthorityProvider {

    @Override
    public List<String> getProtectedResources() {
        return List.of(
                "sub_roles",
                "roles",
                "users"
        );
    }

    @Override
    public List<String> getBaseActions() {
        return List.of(
                "create",
                "read",
                "update",
                "delete"
        );
    }

    @Override
    public List<String> getExtraActionsForResource(String resource) {
        return switch (resource) {
            case "users" -> List.of("enable", "disable");
            default -> List.of();
        };
    }
}