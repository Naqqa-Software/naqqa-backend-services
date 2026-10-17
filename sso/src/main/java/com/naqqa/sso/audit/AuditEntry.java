package com.naqqa.sso.audit;

public record AuditEntry(long seq, AuditEvent event, String prevHash, String hash, String keyId, String signature) {
}
