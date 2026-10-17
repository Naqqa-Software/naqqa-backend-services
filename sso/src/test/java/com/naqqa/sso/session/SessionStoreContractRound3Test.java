package com.naqqa.sso.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class SessionStoreContractRound3Test {

    @Test
    @DisplayName("SSO-T-session-042 F-CORE-22 SessionStore.replace has no find-then-save default: every store must replace atomically and never upsert")
    void replaceIsAbstract() throws Exception {
        assertFalse(SessionStore.class.getMethod("replace", Session.class).isDefault());
    }
}
