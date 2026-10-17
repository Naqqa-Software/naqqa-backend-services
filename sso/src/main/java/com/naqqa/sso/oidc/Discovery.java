package com.naqqa.sso.oidc;

import com.naqqa.sso.crypto.SigningAlgorithm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Discovery {
    static final List<String> SCOPES = List.of("openid", "profile", "email", "phone", "address", "offline_access");
    static final List<String> AUTH_METHODS = List.of("client_secret_basic", "client_secret_post", "private_key_jwt", "none");
    static final List<String> ASYMMETRIC = List.of("RS256", "ES256", "EdDSA");

    private Discovery() {
    }

    static Map<String, Object> document(OidcConfig c, List<SigningAlgorithm> algs) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("issuer", c.issuer());
        d.put("authorization_endpoint", c.authorizationEndpoint());
        d.put("token_endpoint", c.tokenEndpoint());
        d.put("userinfo_endpoint", c.userinfoEndpoint());
        d.put("jwks_uri", c.jwksUri());
        d.put("end_session_endpoint", c.endSessionEndpoint());
        d.put("revocation_endpoint", c.revocationEndpoint());
        d.put("introspection_endpoint", c.introspectionEndpoint());
        d.put("device_authorization_endpoint", c.deviceAuthorizationEndpoint());
        d.put("pushed_authorization_request_endpoint", c.parEndpoint());
        if (c.dynamicRegistrationEnabled()) {
            d.put("registration_endpoint", c.registrationEndpoint());
        }
        d.put("scopes_supported", SCOPES);
        d.put("response_types_supported", List.of("code"));
        d.put("response_modes_supported", List.of("query"));
        d.put("grant_types_supported", List.of(GrantTypes.AUTHORIZATION_CODE, GrantTypes.REFRESH_TOKEN,
                GrantTypes.CLIENT_CREDENTIALS, GrantTypes.DEVICE_CODE));
        d.put("subject_types_supported", List.of("public"));
        List<String> idAlgs = new ArrayList<>();
        for (SigningAlgorithm a : algs) {
            idAlgs.add(a.jwa());
        }
        d.put("id_token_signing_alg_values_supported", idAlgs);
        d.put("token_endpoint_auth_methods_supported", AUTH_METHODS);
        d.put("token_endpoint_auth_signing_alg_values_supported", ASYMMETRIC);
        d.put("introspection_endpoint_auth_methods_supported", AUTH_METHODS.subList(0, 3));
        d.put("introspection_endpoint_auth_signing_alg_values_supported", ASYMMETRIC);
        d.put("revocation_endpoint_auth_methods_supported", AUTH_METHODS);
        d.put("revocation_endpoint_auth_signing_alg_values_supported", ASYMMETRIC);
        d.put("code_challenge_methods_supported", List.of("S256"));
        d.put("claims_supported", List.of("sub", "iss", "aud", "exp", "iat", "auth_time", "nonce", "acr", "amr", "azp", "sid",
                "at_hash", "name", "family_name", "given_name", "middle_name", "nickname", "preferred_username", "profile",
                "picture", "website", "gender", "birthdate", "zoneinfo", "locale", "updated_at", "email", "email_verified",
                "phone_number", "phone_number_verified", "address"));
        d.put("prompt_values_supported", List.of("none", "login", "consent", "select_account"));
        d.put("acr_values_supported", c.acrValuesSupported());
        d.put("claims_parameter_supported", false);
        d.put("request_parameter_supported", false);
        d.put("request_uri_parameter_supported", false);
        d.put("require_pushed_authorization_requests", false);
        d.put("authorization_response_iss_parameter_supported", true);
        d.put("backchannel_logout_supported", true);
        d.put("backchannel_logout_session_supported", true);
        d.put("frontchannel_logout_supported", false);
        d.put("frontchannel_logout_session_supported", false);
        d.put("dpop_signing_alg_values_supported", ASYMMETRIC);
        return d;
    }
}
