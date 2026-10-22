package com.naqqa.sso.risk;

import java.util.List;

public record RiskDecision(Action action, int score, List<String> reasons) {
    public enum Action {
        ALLOW, STEP_UP, DENY
    }

    public RiskDecision {
        reasons = List.copyOf(reasons);
    }
}
