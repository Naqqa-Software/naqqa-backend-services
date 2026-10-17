package com.naqqa.auth.config;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

public record RSAKeys(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
}
