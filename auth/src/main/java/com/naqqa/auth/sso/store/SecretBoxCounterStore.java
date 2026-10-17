package com.naqqa.auth.sso.store;

public interface SecretBoxCounterStore {
    long reserve(String kid, long amount);

    void recordUsed(String kid, long used);
}
