package com.naqqa.auth.service.security;

import org.springframework.stereotype.Service;

import java.util.Random;

@Service
public class CodeGenService {

    // Function to generate a random string of length 6
    public static String generateRandomString() {
        // Define the character set for the random string
        String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        Random random = new Random();

        // StringBuilder to store the generated string
        StringBuilder randomString = new StringBuilder();

        // Loop to generate 6 random characters
        for (int i = 0; i < 6; i++) {
            int randomIndex = random.nextInt(characters.length());
            randomString.append(characters.charAt(randomIndex));
        }

        return randomString.toString();
    }
}

