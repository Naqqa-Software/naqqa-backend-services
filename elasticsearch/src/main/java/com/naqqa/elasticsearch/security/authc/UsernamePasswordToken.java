package com.naqqa.elasticsearch.security.authc;

public record UsernamePasswordToken(String username, char[] password) implements AuthenticationToken {
}
