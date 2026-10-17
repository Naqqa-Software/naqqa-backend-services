package com.naqqa.auth.service.security;

import com.naqqa.auth.security.AuthPrincipal;
import com.naqqa.sso.SsoException;
import com.naqqa.sso.crypto.KeyRing;
import com.naqqa.sso.jose.Jws;
import com.naqqa.sso.jose.Jwt;
import com.naqqa.sso.jose.JwtValidator;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;

@Component
public class AccessTokenVerifier {

    private final KeyRing ring;
    private final JwtValidator validator;

    public AccessTokenVerifier(KeyRing authKeyRing, Clock clock) {
        this.ring = authKeyRing;
        this.validator = JwtValidator.builder(clock)
                .issuer(TokenService.ISSUER)
                .anyAudience()
                .type(Jwt.TYP_JWT)
                .requireClaim("sub")
                .requireClaim(TokenService.TOKEN_USE_CLAIM)
                .build();
    }

    public AuthPrincipal verify(String token) {
        if (token == null || token.isEmpty()) {
            throw new SsoException("token.missing");
        }
        Map<String, Object> header = Jws.peekHeader(token);
        if (!(header.get("typ") instanceof String typ) || !typ.equals(Jwt.TYP_JWT)) {
            throw new SsoException("jwt.typ_mismatch");
        }
        Jwt.Verified verified = header.get("kid") == null
                ? Jwt.verify(token, ring.active(), validator)
                : validator.verify(token, ring);
        Map<String, Object> claims = verified.claims().values();
        if (!TokenService.TOKEN_USE_ACCESS.equals(claims.get(TokenService.TOKEN_USE_CLAIM))
                || claims.containsKey("nonce") || claims.containsKey("at_hash")) {
            throw new SsoException("jwt.not_access_token");
        }
        return new AuthPrincipal(token, verified.header(), claims);
    }
}
