package com.naqqa.sso.oidc;

import java.util.Map;
import java.util.Optional;

public interface ClaimsSource {
    Optional<Map<String, Object>> claims(String tenantId, String userId);
}
