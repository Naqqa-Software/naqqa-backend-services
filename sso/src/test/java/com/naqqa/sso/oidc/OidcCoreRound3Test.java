package com.naqqa.sso.oidc;

import com.naqqa.sso.SsoException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OidcCoreRound3Test {

    @Test
    @DisplayName("SSO-T-oidc-141 F-CORE-24 back-channel logout URI: trailing-dot and upper-case spellings of blocked hosts are blocked too")
    void backchannelHostSpellings() {
        for (String bad : List.of("https://169.254.169.254./bc", "https://10.0.0.5./bc", "https://[::ffff:a9fe:a9fe]/bc")) {
            assertThrows(SsoException.class, () -> RedirectUris.validateBackchannel(bad, true), bad);
        }
        for (String bad : List.of("https://localhost./bc", "https://LOCALHOST/bc", "https://a.localhost./bc", "https://127.0.0.1./bc")) {
            assertThrows(SsoException.class, () -> RedirectUris.validateBackchannel(bad, false), bad);
        }
        assertDoesNotThrow(() -> RedirectUris.validateBackchannel("https://app.example.com/bc", false));
    }
}
