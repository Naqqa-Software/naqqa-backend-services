package com.naqqa.auth.architecture;

import com.naqqa.auth.AuthApplication;
import com.naqqa.auth.sso.flow.MfaEnrollmentGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.RequestMethod;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MfaEnrollmentGateArchitectureTest {

    static final String ENGINE = "com/naqqa/auth/sso/flow/AssuranceEngine";
    static final String SESSIONS = "com/naqqa/auth/sso/flow/SsoSessionManager";
    static final String GATE = "com/naqqa/auth/sso/flow/MfaEnrollmentGate";
    static final Set<String> FLAG = Set.of("requireEnrollment", "releaseEnrollment", "enrollmentRequired");

    @Test
    @DisplayName("SSO-T-auth-755 F-CONF-16 the enrollment gate runs in the access token filter for cookie and Bearer alike, decides through AssuranceEngine, only the engine touches the session restriction, and every allowlisted route is a real endpoint")
    void gateIsWiredThroughTheEngine() {
        Bytecode code = Bytecode.of(AuthApplication.class, "com/naqqa/auth");
        List<String> problems = new ArrayList<>();
        List<Bytecode.Ref> refs = new ArrayList<>();
        code.methods().forEach(m -> refs.add(m.ref));
        Bytecode.Ref filter = refs.stream()
                .filter(r -> r.owner().equals("com/naqqa/auth/security/AccessTokenAuthenticationFilter") && r.name().equals("doFilterInternal"))
                .findFirst().orElseThrow();
        if (!code.reaches(filter, 3, r -> r.owner().equals("com/naqqa/auth/security/AccessSessionGuard") && r.name().equals("enrollmentGateAllows"))) {
            problems.add("AccessTokenAuthenticationFilter does not consult the enrollment gate");
        }
        for (String name : List.of("allows", "onRefresh")) {
            List<Bytecode.Ref> found = refs.stream().filter(r -> r.owner().equals(GATE) && r.name().equals(name)).toList();
            if (found.isEmpty()) {
                problems.add("MfaEnrollmentGate#" + name + " not found");
            }
            for (Bytecode.Ref r : found) {
                if (!code.reaches(r, 3, t -> t.owner().equals(ENGINE))) {
                    problems.add("MfaEnrollmentGate#" + name + " decides without AssuranceEngine");
                }
            }
        }
        for (Bytecode.Info m : code.methods()) {
            String owner = m.ref.owner();
            if (owner.equals(ENGINE) || owner.equals(SESSIONS)) {
                continue;
            }
            for (Bytecode.Ref c : m.calls) {
                if (c.owner().equals(SESSIONS) && FLAG.contains(c.name())) {
                    problems.add(owner + "#" + m.ref.name() + " touches the session restriction outside AssuranceEngine");
                }
            }
        }
        List<Endpoints.Endpoint> endpoints = Endpoints.of(AuthApplication.class);
        AntPathMatcher matcher = new AntPathMatcher();
        for (MfaEnrollmentGate.Route route : MfaEnrollmentGate.routes(Endpoints.BASE, List.of())) {
            boolean found = endpoints.stream().anyMatch(e -> e.methods().contains(RequestMethod.valueOf(route.method()))
                    && e.paths().stream().anyMatch(p -> matcher.match(route.pattern(), p.replaceAll("\\{[^}]+}", "ab12"))));
            if (!found) {
                problems.add("allowlisted route " + route + " matches no endpoint");
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }
}
