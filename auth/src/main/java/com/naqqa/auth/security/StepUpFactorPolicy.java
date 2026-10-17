package com.naqqa.auth.security;

public interface StepUpFactorPolicy {
    boolean satisfied(AuthPrincipal principal);

    boolean acrSatisfied(AuthPrincipal principal, String requiredAcr);
}
