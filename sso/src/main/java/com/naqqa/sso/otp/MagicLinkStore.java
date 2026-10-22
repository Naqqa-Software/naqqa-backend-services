package com.naqqa.sso.otp;

import java.util.Optional;

public interface MagicLinkStore {
    void save(MagicLinkRecord record);

    Optional<MagicLinkRecord> find(String tokenHash);

    boolean markUsed(String tokenHash);
}
