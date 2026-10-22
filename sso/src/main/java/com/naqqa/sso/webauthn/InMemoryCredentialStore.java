package com.naqqa.sso.webauthn;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryCredentialStore implements CredentialStore {
    private final ConcurrentHashMap<String, CredentialRecord> byId = new ConcurrentHashMap<>();

    private static String key(byte[] id) {
        return Bytes.b64url(id);
    }

    @Override
    public void create(CredentialRecord record) {
        if (byId.putIfAbsent(key(record.credentialId()), record) != null) {
            throw new SsoException("webauthn.credential_exists");
        }
    }

    @Override
    public Optional<CredentialRecord> findById(byte[] credentialId) {
        if (credentialId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(key(credentialId)));
    }

    @Override
    public List<CredentialRecord> findByUserHandle(byte[] userHandle) {
        List<CredentialRecord> out = new ArrayList<>();
        for (CredentialRecord r : byId.values()) {
            if (Arrays.equals(r.userHandle(), userHandle)) {
                out.add(r);
            }
        }
        return out;
    }

    @Override
    public List<CredentialRecord> findByUserId(String userId) {
        List<CredentialRecord> out = new ArrayList<>();
        for (CredentialRecord r : byId.values()) {
            if (r.userId().equals(userId)) {
                out.add(r);
            }
        }
        return out;
    }

    @Override
    public boolean update(CredentialRecord previous, CredentialRecord next) {
        if (!Arrays.equals(previous.credentialId(), next.credentialId())) {
            throw new SsoException("webauthn.credential.invalid");
        }
        boolean[] done = {false};
        byId.computeIfPresent(key(next.credentialId()), (k, current) -> {
            if (current.signCount() == previous.signCount() && current.cloneDetected() == previous.cloneDetected()
                    && current.backupState() == previous.backupState()
                    && Objects.equals(current.lastUsedAt(), previous.lastUsedAt())) {
                done[0] = true;
                return next;
            }
            return current;
        });
        return done[0];
    }

    @Override
    public boolean delete(byte[] credentialId) {
        return byId.remove(key(credentialId)) != null;
    }
}
