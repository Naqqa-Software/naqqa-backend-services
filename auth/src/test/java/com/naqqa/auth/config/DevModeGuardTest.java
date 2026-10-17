package com.naqqa.auth.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevModeGuardTest {

    static final List<String> SWITCHES = List.of(
            "naqqa.sso.social.test-mode=true",
            "naqqa.sso.oidc-provider.backchannel.test-mode=true",
            "naqqa.sso.risk.signal-override=true",
            "naqqa.sso.ldap.directories.corp.allow-plain-for-tests=true",
            "naqqa.sso.session.cookie-secure=false",
            "app.cookie.secure=false");

    static String messages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getMessage()).append('\n');
        }
        return sb.toString();
    }

    static boolean devOnly(Throwable failure) {
        return failure != null && messages(failure).contains("is a dev-only switch");
    }

    static ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(AuthCoreConfiguration.class);
    }

    @Test
    @DisplayName("SSO-T-auth-747 F-CONF-17 dev-only switches are refused at startup without naqqa.sso.dev-mode=true, whatever the profile (none, default, a consumer profile such as docker, prod); with dev-mode they start")
    void devSwitchesAreDefaultDeny() {
        for (String profile : List.of("", "default", "docker", "prod")) {
            for (String sw : SWITCHES) {
                String key = sw.substring(0, sw.indexOf('='));
                runner().withInitializer(c -> {
                            if (!profile.isEmpty()) {
                                c.getEnvironment().setActiveProfiles(profile);
                            }
                        })
                        .withPropertyValues(sw)
                        .run(ctx -> {
                            Throwable failure = ctx.getStartupFailure();
                            assertTrue(failure != null && messages(failure).contains(key + " is a dev-only switch"),
                                    "profile '" + profile + "' " + sw + ": " + failure);
                        });
                runner().withPropertyValues(sw, "naqqa.sso.dev-mode=true")
                        .run(ctx -> assertFalse(devOnly(ctx.getStartupFailure()), sw + " with dev-mode"));
            }
        }
        runner().run(ctx -> assertFalse(devOnly(ctx.getStartupFailure())));
        runner().withPropertyValues("naqqa.sso.dev-mode=false", "naqqa.sso.social.test-mode=true")
                .run(ctx -> assertTrue(devOnly(ctx.getStartupFailure())));
    }
}
