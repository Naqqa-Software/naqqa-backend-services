package com.naqqa.sso.oidc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InMemoryOidcStore implements OidcStore {
    private final Map<String, AuthorizationCode> codes = new HashMap<>();
    private final Map<String, PushedRequest> pushed = new HashMap<>();
    private final Map<String, PendingAuthorization> pending = new HashMap<>();
    private final Map<String, DeviceAuthorization> devices = new HashMap<>();
    private final Map<String, String> userCodes = new HashMap<>();
    private final Map<String, GrantRecord> grants = new HashMap<>();
    private final Map<String, String> refreshIndex = new HashMap<>();
    private final Map<String, Instant> once = new HashMap<>();
    private final Map<String, Instant> revokedAccess = new HashMap<>();

    @Override
    public synchronized void saveCode(AuthorizationCode code) {
        codes.put(code.codeHash(), code);
    }

    @Override
    public synchronized Optional<AuthorizationCode> consumeCode(String codeHash) {
        AuthorizationCode c = codes.get(codeHash);
        if (c == null) {
            return Optional.empty();
        }
        if (!c.used()) {
            codes.put(codeHash, c.markUsed());
        }
        return Optional.of(c);
    }

    @Override
    public synchronized void savePushed(PushedRequest request) {
        pushed.put(request.handleHash(), request);
    }

    @Override
    public synchronized Optional<PushedRequest> consumePushed(String handleHash) {
        return Optional.ofNullable(pushed.remove(handleHash));
    }

    @Override
    public synchronized void savePending(PendingAuthorization p) {
        pending.put(p.handleHash(), p);
    }

    @Override
    public synchronized Optional<PendingAuthorization> findPending(String handleHash) {
        return Optional.ofNullable(pending.get(handleHash));
    }

    @Override
    public synchronized void deletePending(String handleHash) {
        pending.remove(handleHash);
    }

    @Override
    public synchronized boolean saveDeviceIfUserCodeFree(DeviceAuthorization device) {
        String existing = userCodes.get(device.userCode());
        if (existing != null && devices.containsKey(existing)) {
            DeviceAuthorization d = devices.get(existing);
            if (d.status() != DeviceAuthorization.Status.CONSUMED) {
                return false;
            }
        }
        devices.put(device.deviceCodeHash(), device);
        userCodes.put(device.userCode(), device.deviceCodeHash());
        return true;
    }

    @Override
    public synchronized Optional<DeviceAuthorization> findDeviceByCode(String deviceCodeHash) {
        return Optional.ofNullable(devices.get(deviceCodeHash));
    }

    @Override
    public synchronized Optional<DeviceAuthorization> findDeviceByUserCode(String userCode) {
        String h = userCodes.get(userCode);
        return h == null ? Optional.empty() : Optional.ofNullable(devices.get(h));
    }

    @Override
    public synchronized boolean replaceDevice(DeviceAuthorization expected, DeviceAuthorization next) {
        DeviceAuthorization current = devices.get(expected.deviceCodeHash());
        if (current == null || !current.equals(expected)) {
            return false;
        }
        devices.put(next.deviceCodeHash(), next);
        return true;
    }

    @Override
    public synchronized void saveGrant(GrantRecord grant) {
        grants.put(grant.grantId(), grant);
    }

    @Override
    public synchronized Optional<GrantRecord> findGrant(String grantId) {
        return grantId == null ? Optional.empty() : Optional.ofNullable(grants.get(grantId));
    }

    @Override
    public synchronized void indexRefresh(String refreshHash, String grantId) {
        refreshIndex.put(refreshHash, grantId);
    }

    @Override
    public synchronized Optional<String> grantIdForRefresh(String refreshHash) {
        return Optional.ofNullable(refreshIndex.get(refreshHash));
    }

    @Override
    public synchronized List<GrantRecord> grantsForSid(String sid) {
        List<GrantRecord> out = new ArrayList<>();
        for (GrantRecord g : grants.values()) {
            if (sid != null && sid.equals(g.sid())) {
                out.add(g);
            }
        }
        return out;
    }

    @Override
    public synchronized boolean markOnce(String key, Instant expiresAt) {
        Instant prev = once.get(key);
        if (prev != null) {
            return false;
        }
        once.put(key, expiresAt);
        return true;
    }

    @Override
    public synchronized void markRevoked(String key, Instant expiresAt) {
        revokedAccess.put(key, expiresAt);
    }

    @Override
    public synchronized boolean isRevoked(String key) {
        return revokedAccess.containsKey(key);
    }

    @Override
    public synchronized int purgeExpired(Instant now) {
        int before = codes.size() + pushed.size() + pending.size() + once.size() + revokedAccess.size() + devices.size();
        codes.values().removeIf(c -> !now.isBefore(c.expiresAt().plusSeconds(3600)));
        pushed.values().removeIf(p -> !now.isBefore(p.expiresAt()));
        pending.values().removeIf(p -> !now.isBefore(p.expiresAt()));
        once.values().removeIf(t -> !now.isBefore(t));
        revokedAccess.values().removeIf(t -> !now.isBefore(t));
        devices.values().removeIf(d -> !now.isBefore(d.expiresAt()));
        userCodes.values().removeIf(h -> !devices.containsKey(h));
        int after = codes.size() + pushed.size() + pending.size() + once.size() + revokedAccess.size() + devices.size();
        return before - after;
    }
}
