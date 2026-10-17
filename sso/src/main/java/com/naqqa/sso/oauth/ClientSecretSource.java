package com.naqqa.sso.oauth;

@FunctionalInterface
public interface ClientSecretSource {
    String secret();

    static ClientSecretSource fixed(String value) {
        return new ClientSecretSource() {
            @Override
            public String secret() {
                return value;
            }

            @Override
            public String toString() {
                return "ClientSecretSource[redacted]";
            }
        };
    }
}
