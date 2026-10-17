package com.naqqa.sso.otp;

import java.util.Optional;
import java.util.OptionalInt;

public interface PushApprovalStore {
    void create(PushApprovalRecord record);

    Optional<PushApprovalRecord> find(String id);

    boolean transition(String id, PushApproval.Status from, PushApproval.Status to);

    OptionalInt addMismatch(String id);
}
