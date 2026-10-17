package com.naqqa.sso.audit;

public record VerifyResult(boolean ok, long badSeq, String reason) {
    public static VerifyResult good() {
        return new VerifyResult(true, -1, null);
    }

    public static VerifyResult bad(long seq, String reason) {
        return new VerifyResult(false, seq, reason);
    }
}
