package com.naqqa.auth.service.security;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;

public interface DeviceSessionService {
    void handleDeviceLogin(UserEntity user, String deviceId, String clientType, RoleEntity activeRole);
    void revokeDevice(UserEntity user, String deviceId);
}