package com.naqqa.elasticsearch.security.authc;

import com.naqqa.elasticsearch.security.authz.RoleDescriptor;
import com.naqqa.elasticsearch.security.support.WildcardMatcher;

import java.util.List;

public final class RunAsService {

    public static final String RUN_AS_HEADER = "es-security-runas-user";

    private RunAsService() {
    }

    public static boolean isPermitted(List<RoleDescriptor> authenticatedUserRoles, String runAsUsername) {
        return authenticatedUserRoles.stream()
                .flatMap(r -> r.runAs() == null ? java.util.stream.Stream.<String>empty() : r.runAs().stream())
                .anyMatch(pattern -> WildcardMatcher.matches(pattern, runAsUsername));
    }
}
