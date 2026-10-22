package com.naqqa.sso.mfa;

public interface TotpStepStore {
    long lastStep(String userId);

    boolean advance(String userId, long step);

    void clear(String userId);
}
