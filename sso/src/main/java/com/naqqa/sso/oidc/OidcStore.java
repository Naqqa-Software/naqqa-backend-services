package com.naqqa.sso.oidc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OidcStore {
    void saveCode(AuthorizationCode code);

    Optional<AuthorizationCode> consumeCode(String codeHash);

    void savePushed(PushedRequest request);

    Optional<PushedRequest> consumePushed(String handleHash);

    void savePending(PendingAuthorization pending);

    Optional<PendingAuthorization> findPending(String handleHash);

    void deletePending(String handleHash);

    boolean saveDeviceIfUserCodeFree(DeviceAuthorization device);

    Optional<DeviceAuthorization> findDeviceByCode(String deviceCodeHash);

    Optional<DeviceAuthorization> findDeviceByUserCode(String userCode);

    boolean replaceDevice(DeviceAuthorization expected, DeviceAuthorization next);

    void saveGrant(GrantRecord grant);

    Optional<GrantRecord> findGrant(String grantId);

    void indexRefresh(String refreshHash, String grantId);

    Optional<String> grantIdForRefresh(String refreshHash);

    List<GrantRecord> grantsForSid(String sid);

    boolean markOnce(String key, Instant expiresAt);

    void markRevoked(String key, Instant expiresAt);

    boolean isRevoked(String key);

    int purgeExpired(Instant now);
}
