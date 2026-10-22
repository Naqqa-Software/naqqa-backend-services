package com.naqqa.auth.service.security;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;

@Service
public class CodeGenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    public static String generateRandomString() {
        String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = RANDOM;

        StringBuilder randomString = new StringBuilder();

        for (int i = 0; i < 6; i++) {
            int randomIndex = random.nextInt(characters.length());
            randomString.append(characters.charAt(randomIndex));
        }

        return randomString.toString();
    }
}

