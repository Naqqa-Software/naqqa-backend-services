package com.naqqa.auth.service.security;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "app.security.device-tracking.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpDeviceSessionService implements DeviceSessionService {
    @Override
    public void handleDeviceLogin(UserEntity user, String deviceId, String clientType, RoleEntity activeRole) {
        // Do nothing
    }

    @Override
    public void revokeDevice(UserEntity user, String deviceId) {
        // Do nothing
    }
}