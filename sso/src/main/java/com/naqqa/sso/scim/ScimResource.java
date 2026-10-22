package com.naqqa.sso.scim;

import com.naqqa.sso.SsoException;
import java.time.Instant;
import java.util.Map;

public record ScimResource(
        String tenantId,
        String id,
        Map<String, Object> attributes,
        long version,
        Instant created,
        Instant lastModified) {

    public ScimResource {
        if (tenantId == null || tenantId.isBlank() || id == null || id.isBlank() || attributes == null
                || version < 1 || created == null || lastModified == null) {
            throw new SsoException("scim.resource_invalid");
        }
        attributes = ScimData.map(ScimData.immutable(attributes));
    }

    public ScimResource withAttributes(Map<String, Object> next, Instant at) {
        return new ScimResource(tenantId, id, next, version + 1, created, at);
    }
}
