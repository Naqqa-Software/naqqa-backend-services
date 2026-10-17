package com.naqqa.auth.service.security;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;

@Service
public class CodeGenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    // Function to generate a random string of length 6
    public static String generateRandomString() {
        // Define the character set for the random string
        String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

        // StringBuilder to store the generated string
        StringBuilder randomString = new StringBuilder();

        // Loop to generate 6 random characters
        for (int i = 0; i < 6; i++) {
            int randomIndex = RANDOM.nextInt(characters.length());
            randomString.append(characters.charAt(randomIndex));
        }

        return randomString.toString();
    }
}

