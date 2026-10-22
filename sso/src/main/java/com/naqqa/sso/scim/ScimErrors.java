package com.naqqa.sso.scim;

import com.naqqa.sso.SsoException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ScimErrors {
    private record Entry(int status, String scimType, String detail) {
    }

    private static final Map<String, Entry> TABLE = Map.ofEntries(
            Map.entry("scim.invalid_filter", new Entry(400, "invalidFilter", "The specified filter syntax was invalid")),
            Map.entry("scim.invalid_path", new Entry(400, "invalidPath", "The path attribute was invalid")),
            Map.entry("scim.invalid_syntax", new Entry(400, "invalidSyntax", "The request body was malformed")),
            Map.entry("scim.invalid_value", new Entry(400, "invalidValue", "A required value was missing or invalid")),
            Map.entry("scim.invalid_version", new Entry(400, "invalidVers", "The specified version was invalid")),
            Map.entry("scim.too_many", new Entry(400, "tooMany", "The request exceeds the allowed size")),
            Map.entry("scim.no_target", new Entry(400, "noTarget", "The specified path did not yield a target")),
            Map.entry("scim.mutability", new Entry(400, "mutability", "The attribute is not modifiable")),
            Map.entry("scim.uniqueness", new Entry(409, "uniqueness", "The value is already in use")),
            Map.entry("scim.not_found", new Entry(404, null, "Resource not found")),
            Map.entry("scim.version_conflict", new Entry(412, null, "The resource was modified, reload and retry")),
            Map.entry("scim.precondition_failed", new Entry(412, null, "The precondition failed")),
            Map.entry("scim.precondition_required", new Entry(428, null, "If-Match header is required")),
            Map.entry("scim.unsupported", new Entry(501, null, "Not supported")));

    private ScimErrors() {
    }

    public static ScimResponse of(Throwable t) {
        if (t instanceof SsoException e) {
            Entry entry = TABLE.get(e.code());
            if (entry == null && e.code().startsWith("json.")) {
                entry = TABLE.get("scim.invalid_syntax");
            }
            if (entry == null && e.code().startsWith("scim.password")) {
                entry = TABLE.get("scim.invalid_value");
            }
            if (entry != null) {
                return build(entry.status, entry.scimType, entry.detail);
            }
            if (e.code().startsWith("scim.")) {
                return build(400, "invalidValue", "The request was rejected");
            }
        }
        return build(500, null, "Internal error");
    }

    public static ScimResponse of(String code) {
        return of(new SsoException(code));
    }

    private static ScimResponse build(int status, String scimType, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemas", List.of(ScimSchemas.ERROR));
        body.put("status", Integer.toString(status));
        if (scimType != null) {
            body.put("scimType", scimType);
        }
        body.put("detail", detail);
        return new ScimResponse(status, body, Map.of());
    }
}
