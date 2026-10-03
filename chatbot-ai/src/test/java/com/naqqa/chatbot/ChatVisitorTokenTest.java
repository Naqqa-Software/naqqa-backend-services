package com.naqqa.chatbot;

import com.naqqa.chatbot.security.ChatVisitorTokenService;
import com.naqqa.chatbot.service.ChatException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatVisitorTokenTest {

    private static JwtEncoder encoder;
    private static ChatVisitorTokenService service;
    private static ChatVisitorTokenService otherKeyService;

    @BeforeAll
    static void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        RSAKey key = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey((RSAPrivateKey) pair.getPrivate()).build();
        encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        service = new ChatVisitorTokenService(encoder, (RSAPublicKey) pair.getPublic(), 24, "omy-chat");
        KeyPair other = generator.generateKeyPair();
        otherKeyService = new ChatVisitorTokenService(encoder, (RSAPublicKey) other.getPublic(), 24, "omy-chat");
    }

    @Test
    void tokenIsBoundToItsConversation() {
        String token = service.issue("conv-a");
        assertEquals("conv-a", service.conversationIdOf(token));
        assertTrue(service.isValidFor(token, "conv-a"));
        assertFalse(service.isValidFor(token, "conv-b"));
        ChatException ex = assertThrows(ChatException.class, () -> service.verify(token, "conv-b"));
        assertEquals(ChatException.FORBIDDEN, ex.getErrorKey());
    }

    @Test
    void missingOrGarbageTokenIsRejected() {
        assertThrows(ChatException.class, () -> service.verify(null, "conv-a"));
        assertThrows(ChatException.class, () -> service.verify("not-a-jwt", "conv-a"));
    }

    @Test
    void userAccessTokenIsNotAcceptedAsVisitorToken() {
        Instant now = Instant.now();
        String userToken = encoder.encode(JwtEncoderParameters.from(JwtClaimsSet.builder()
                .issuer("omy-server").issuedAt(now).expiresAt(now.plus(1, ChronoUnit.HOURS))
                .subject("conv-a").claim("authorities", "chat:read_all").build())).getTokenValue();
        assertNull(service.conversationIdOf(userToken));
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        String token = service.issue("conv-a");
        assertFalse(otherKeyService.isValidFor(token, "conv-a"));
    }
}
