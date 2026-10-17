package com.naqqa.sso.scim;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.json.Json;
import com.naqqa.sso.json.JsonLimits;
import java.util.Map;

public final class ScimJson {
    public static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final JsonLimits LIMITS = new JsonLimits(32, MAX_BODY_BYTES);

    private ScimJson() {
    }

    public static Map<String, Object> parse(byte[] body) {
        if (body == null || body.length == 0) {
            throw new SsoException("scim.invalid_syntax");
        }
        return Json.parseObject(body, LIMITS);
    }

    public static ScimResponse parseOrError(byte[] body, java.util.function.Function<Map<String, Object>, ScimResponse> next) {
        Map<String, Object> parsed;
        try {
            parsed = parse(body);
        } catch (SsoException e) {
            return ScimErrors.of(new SsoException("scim.invalid_syntax"));
        }
        return next.apply(parsed);
    }

    public static String write(ScimResponse response) {
        return response.body().isEmpty() ? "" : Json.write(response.body());
    }
}
