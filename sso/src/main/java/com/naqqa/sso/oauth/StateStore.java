package com.naqqa.sso.oauth;

import java.util.Optional;

public interface StateStore {
    void put(StateRecord record);

    Optional<StateRecord> consume(String stateHash);
}
