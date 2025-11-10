package com.naqqa.auth.roles;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

@Component
@Primary
public class DefaultRoleProvider implements RoleProvider {
    @Override
    public Collection<String> getRoles() {
        return List.of("USER", "ADMIN");
    }

    @Override
    public String getDefaultRole() {
        return "USER";
    }
}
