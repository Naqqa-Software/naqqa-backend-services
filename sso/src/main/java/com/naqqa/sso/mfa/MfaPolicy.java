package com.naqqa.sso.mfa;

import com.naqqa.sso.SsoException;

import java.time.Duration;
import java.util.Set;

public record MfaPolicy(Set<String> requiredRoles, Set<Factor> allowedFactors, Duration stepUpMaxAge, Duration rememberedDeviceTtl) {
    public MfaPolicy {
        if (requiredRoles == null || allowedFactors == null || stepUpMaxAge == null || rememberedDeviceTtl == null
                || stepUpMaxAge.isNegative() || rememberedDeviceTtl.isNegative()) {
            throw new SsoException("mfa_policy_invalid");
        }
        requiredRoles = Set.copyOf(requiredRoles);
        allowedFactors = Set.copyOf(allowedFactors);
    }

    public static MfaPolicy defaults() {
        return new MfaPolicy(Set.of(), Set.of(Factor.values()), Duration.ofMinutes(15), Duration.ofDays(30));
    }

    public boolean isRequiredFor(Set<String> roles) {
        for (String r : roles) {
            if (requiredRoles.contains(r)) {
                return true;
            }
        }
        return false;
    }

    public boolean allows(Factor factor) {
        return allowedFactors.contains(factor);
    }

    public Set<String> allowedAmr() {
        java.util.HashSet<String> out = new java.util.HashSet<>();
        for (Factor f : allowedFactors) {
            out.addAll(f.amr());
        }
        return out;
    }
}
