package com.naqqa.auth.architecture;

import com.naqqa.auth.AuthApplication;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.service.security.TokenService;
import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.sso.crypto.KeyRing;
import com.naqqa.sso.crypto.SigningAlgorithm;
import com.naqqa.sso.crypto.SigningKey;
import com.naqqa.sso.mfa.StepUp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenIssuerArchitectureTest {

    static final Set<String> SIGNERS = Set.of("TokenService#generateAccessToken", "MfaTokenService#issue");
    static final Set<String> SAML_SIGNERS = Set.of("com/naqqa/auth/sso/saml/SamlSpConfiguration", "com/naqqa/auth/sso/saml/SelfSignedCertificate");
    static final Duration TTL = Duration.ofMinutes(15);

    @Test
    @DisplayName("SSO-T-auth-607 every access token is minted by TokenService from a full session context")
    void oneIssuer() {
        Bytecode code = Bytecode.of(AuthApplication.class, "com/naqqa/auth");
        Set<String> signers = new TreeSet<>();
        for (Bytecode.Info m : code.methods()) {
            for (Bytecode.Ref c : m.calls) {
                if (c.owner().startsWith("com/naqqa/sso/jose/") && c.name().startsWith("sign")) {
                    signers.add(m.ref.simple());
                }
                if ((c.owner().equals("com/naqqa/sso/crypto/SigningKey") && c.name().equals("sign"))
                        || (c.owner().equals("java/security/Signature") && c.name().equals("sign") && !SAML_SIGNERS.contains(m.ref.owner()))) {
                    signers.add(m.ref.simple() + " signs outside com.naqqa.sso.jose");
                }
            }
        }
        assertEquals(new TreeSet<>(SIGNERS), signers);
        List<String> problems = new ArrayList<>();
        for (Method m : TokenService.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && m.getReturnType() == String.class
                    && code.reaches(Bytecode.Ref.of(m), 2, r -> r.owner().startsWith("com/naqqa/sso/jose/") && r.name().startsWith("sign"))
                    && !Arrays.asList(m.getParameterTypes()).contains(TokenService.AccessContext.class)) {
                problems.add(m + " mints without an AccessContext");
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    static TokenService service(boolean sso, Clock clock) {
        SsoProperties p = new SsoProperties();
        p.setEnabled(sso);
        return new TokenService(KeyRing.of(clock, SigningKey.generate(SigningAlgorithm.RS256)), clock, p);
    }

    static UserEntity user() {
        RoleEntity role = new RoleEntity();
        role.setId(7L);
        role.setName("USER");
        role.setAuthorities(new HashSet<>());
        return UserEntity.builder().id(42L).email("issuer@test").fullName("Issuer").roles(new HashSet<>(Set.of(role))).build();
    }

    @Test
    @DisplayName("SSO-T-auth-608 the issuer refuses partial contexts and never mints a fresh session-less token in SSO mode")
    void contextRules() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-15T10:00:00Z"), java.time.ZoneOffset.UTC);
        Instant now = clock.instant();
        List<String> pwd = List.of("pwd");
        assertThrows(IllegalArgumentException.class, () -> new TokenService.AccessContext("sid", List.of(), StepUp.ACR_PASSWORD, now, null, TTL));
        assertThrows(IllegalArgumentException.class, () -> new TokenService.AccessContext("sid", null, StepUp.ACR_PASSWORD, now, null, TTL));
        assertThrows(IllegalArgumentException.class, () -> new TokenService.AccessContext("sid", pwd, null, now, null, TTL));
        assertThrows(IllegalArgumentException.class, () -> new TokenService.AccessContext("sid", pwd, StepUp.ACR_PASSWORD, null, null, TTL));
        assertThrows(IllegalArgumentException.class, () -> new TokenService.AccessContext(null, pwd, StepUp.ACR_PASSWORD, now,
                Map.of("sub", "1"), TTL));

        UserEntity user = user();
        RoleEntity role = user.getRoles().iterator().next();
        TokenService sso = service(true, clock);
        assertThrows(IllegalArgumentException.class, () -> sso.generateAccessToken(user, role, null));
        assertThrows(IllegalStateException.class, () -> sso.generateAccessToken(user, role,
                new TokenService.AccessContext(null, pwd, StepUp.ACR_PASSWORD, now, null, TTL)));
        assertNotNull(sso.generateAccessToken(user, role,
                new TokenService.AccessContext(null, pwd, StepUp.ACR_PASSWORD, now.minus(Duration.ofHours(1)), null, TTL)));
        assertNotNull(sso.generateAccessToken(user, role, new TokenService.AccessContext("sid", pwd, StepUp.ACR_PASSWORD, now, null, TTL)));
        assertNotNull(service(false, clock).generateAccessToken(user, role,
                new TokenService.AccessContext(null, pwd, StepUp.ACR_PASSWORD, now, null, TTL)));
    }
}
