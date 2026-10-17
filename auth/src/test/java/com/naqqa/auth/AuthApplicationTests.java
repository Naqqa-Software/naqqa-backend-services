package com.naqqa.auth;

import com.naqqa.auth.service.security.AccessTokenVerifier;
import com.naqqa.auth.service.security.TokenService;
import com.naqqa.auth.support.SsoTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthApplicationTests extends SsoTestBase {

    @Autowired
    ApplicationContext context;

    @Test
    @DisplayName("SSO-T-auth-001 F-A3 library context loads with test RSA keys, test RoleProvider, local Mongo and Redis")
    void contextLoads() {
        assertNotNull(context.getBean(TokenService.class));
        assertNotNull(context.getBean(AccessTokenVerifier.class));
        assertFalse(context.containsBean("ssoSecrets"));
        assertTrue(userRepository.findByEmail("admin@naqqa-auth.test").isPresent());
    }
}
