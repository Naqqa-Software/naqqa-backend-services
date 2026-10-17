package com.naqqa.auth.config;

import com.naqqa.sso.crypto.KeyRing;
import com.naqqa.sso.crypto.Pem;
import com.naqqa.sso.crypto.SigningAlgorithm;
import com.naqqa.sso.crypto.SigningKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;

@Configuration
public class RsaKeyConfig {

    @Bean
    public SigningKey authSigningKey(
            @Value("${rsa.public-key}") Resource pub,
            @Value("${rsa.private-key}") Resource priv
    ) {
        SigningKey key = Pem.signingKey(read(priv), read(pub));
        if (key.alg() != SigningAlgorithm.RS256) {
            throw new IllegalStateException("rsa.private-key must be an RSA key");
        }
        return key;
    }

    @Bean
    public KeyRing authKeyRing(SigningKey authSigningKey, Clock clock) {
        return KeyRing.of(clock, authSigningKey);
    }

    @Bean
    public RSAKeys rsaKeys(SigningKey authSigningKey) {
        return new RSAKeys((RSAPublicKey) authSigningKey.publicKey(), (RSAPrivateKey) authSigningKey.privateKey());
    }

    static String read(Resource resource) {
        try {
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("rsa.public-key and rsa.private-key must name readable PEM resources");
        }
    }
}
