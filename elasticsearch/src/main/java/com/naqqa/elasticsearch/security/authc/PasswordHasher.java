package com.naqqa.elasticsearch.security.authc;

public interface PasswordHasher {

    String hash(char[] password);

    boolean verify(char[] password, String hash);

    boolean canHandle(String hash);
}
