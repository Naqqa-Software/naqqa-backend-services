package com.naqqa.sso.mfa;

public interface SecretSealer {
    byte[] seal(byte[] plaintext, byte[] aad);

    byte[] open(byte[] sealed, byte[] aad);
}
