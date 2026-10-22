package com.naqqa.sso.oauth;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.json.Json;
import com.naqqa.sso.json.JsonLimits;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class GithubProfile {
    private static final JsonLimits LIMITS = new JsonLimits(8, 256 * 1024);
    private static final List<String> TYPES = List.of("application/json", "application/vnd.github+json");

    private GithubProfile() {
    }

    static ExternalIdentity fetch(SafeHttp http, ProviderConfig config, String accessToken) {
        Map<String, String> headers = Map.of(
                "Authorization", "Bearer " + accessToken,
                "Accept", "application/vnd.github+json",
                "X-GitHub-Api-Version", "2022-11-28");
        SafeHttp.Response user = http.get(config.userinfoEndpoint(), headers, TYPES);
        if (user.status() != 200) {
            throw new SsoException("oauth.profile_failed");
        }
        Map<String, Object> u;
        Object idValue;
        try {
            u = Json.parseObject(user.body(), LIMITS);
            idValue = u.get("id");
        } catch (SsoException e) {
            throw new SsoException("oauth.profile_invalid");
        }
        if (!(idValue instanceof Long) || (Long) idValue <= 0) {
            throw new SsoException("oauth.profile_invalid");
        }
        String subject = String.valueOf(idValue);
        String login = Sanitize.text(u.get("login"), 100);
        String name = Sanitize.text(u.get("name"), 200);
        String email = null;
        boolean verified = false;
        SafeHttp.Response emails = http.get(config.emailsEndpoint(), headers, TYPES);
        if (emails.status() == 200) {
            try {
                for (Object o : Json.asList(Json.parse(emails.body(), LIMITS))) {
                    Map<String, Object> m = Json.asObject(o);
                    if (Boolean.TRUE.equals(m.get("primary")) && Boolean.TRUE.equals(m.get("verified"))) {
                        String candidate = Sanitize.email(m.get("email"));
                        if (candidate != null) {
                            email = candidate;
                            verified = true;
                            break;
                        }
                    }
                }
            } catch (SsoException e) {
                throw new SsoException("oauth.profile_invalid");
            }
        }
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", subject);
        if (login != null) {
            claims.put("login", login);
        }
        return new ExternalIdentity(config.id(), subject, email, verified, name != null ? name : login, Sanitize.pictureUrl(u.get("avatar_url")), claims);
    }
}
