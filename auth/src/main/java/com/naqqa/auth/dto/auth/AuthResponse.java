package com.naqqa.auth.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AuthResponse {
    private Long id;
    private String email;
    private String fullName;
    private Set<String> roles;
    private RoleSummary role;
    private Set<String> authorities;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class RoleSummary {
        private Long id;
        private String name;
    }
}
