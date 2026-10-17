package com.naqqa.sso.oauth;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.json.Json;
import com.naqqa.sso.json.JsonLimits;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class FacebookProfile {
    private static final JsonLimits LIMITS = new JsonLimits(8, 128 * 1024);
    private static final List<String> TYPES = List.of("application/json", "text/javascript");

    private FacebookProfile() {
    }

    static ExternalIdentity fetch(SafeHttp http, ProviderConfig config, String accessToken, Clock clock) {
        String secret = config.clientSecret().secret();
        String appToken = config.clientId() + "|" + secret;
        SafeHttp.Response debug = http.get(
                config.debugTokenEndpoint() + "?input_token=" + SafeHttp.encode(accessToken),
                Map.of("Authorization", "Bearer " + appToken), TYPES);
        if (debug.status() != 200) {
            throw new SsoException("oauth.facebook_token_invalid");
        }
        String userId;
        try {
            Map<String, Object> data = Json.optObject(Json.parseObject(debug.body(), LIMITS), "data");
            if (data == null || !Boolean.TRUE.equals(data.get("is_valid"))) {
                throw new SsoException("oauth.facebook_token_invalid");
            }
            Object appId = data.get("app_id");
            if (!(appId instanceof String) || !Bytes.constantTimeEquals((String) appId, config.clientId())) {
                throw new SsoException("oauth.facebook_app_mismatch");
            }
            Object exp = data.get("expires_at");
            if (exp instanceof Long && (Long) exp != 0 && (Long) exp <= clock.instant().getEpochSecond()) {
                throw new SsoException("oauth.facebook_token_invalid");
            }
            Object uid = data.get("user_id");
            if (!(uid instanceof String) || ((String) uid).isEmpty()) {
                throw new SsoException("oauth.facebook_token_invalid");
            }
            userId = (String) uid;
        } catch (SsoException e) {
            if (e.code().startsWith("oauth.")) {
                throw e;
            }
            throw new SsoException("oauth.profile_invalid");
        }
        String proof = Bytes.hex(Hashes.hmacSha256(Bytes.utf8(secret), Bytes.utf8(accessToken)));
        SafeHttp.Response me = http.get(
                config.userinfoEndpoint() + "?fields=" + SafeHttp.encode("id,name,email,picture.type(large)")
                        + "&appsecret_proof=" + proof,
                Map.of("Authorization", "Bearer " + accessToken), TYPES);
        if (me.status() != 200) {
            throw new SsoException("oauth.profile_failed");
        }
        try {
            Map<String, Object> m = Json.parseObject(me.body(), LIMITS);
            Object id = m.get("id");
            if (!(id instanceof String) || !Bytes.constantTimeEquals((String) id, userId)) {
                throw new SsoException("oauth.facebook_user_mismatch");
            }
            String picture = null;
            Map<String, Object> pic = Json.optObject(m, "picture");
            if (pic != null) {
                Map<String, Object> pd = Json.optObject(pic, "data");
                picture = pd == null ? null : Sanitize.pictureUrl(pd.get("url"));
            }
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("sub", userId);
            return new ExternalIdentity(config.id(), userId, Sanitize.email(m.get("email")), false,
                    Sanitize.text(m.get("name"), 200), picture, claims);
        } catch (SsoException e) {
            if (e.code().startsWith("oauth.")) {
                throw e;
            }
            throw new SsoException("oauth.profile_invalid");
        }
    }
}
