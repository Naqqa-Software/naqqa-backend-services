package com.naqqa.auth.service.security;

import com.naqqa.auth.entity.auth.UserDeviceEntity;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.repository.RefreshTokenRepository;
import com.naqqa.auth.repository.UserDeviceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.security.device-tracking.enabled", havingValue = "true")
public class DefaultDeviceSessionService implements DeviceSessionService {

    private final UserDeviceRepository userDeviceRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${device.limit:5}")
    private int deviceLimit;

    @Override
    public void handleDeviceLogin(UserEntity user, String deviceId, String clientType, RoleEntity activeRole) {
        long deviceCount = userDeviceRepository.countByUserId(user.getId());

        if (deviceCount >= deviceLimit) {
            List<UserDeviceEntity> oldestDevices = userDeviceRepository.findByUserIdOrderByCreatedAtAsc(user.getId());
            if (!oldestDevices.isEmpty()) {
                UserDeviceEntity oldestDevice = oldestDevices.get(0);
                refreshTokenRepository.deleteByUserAndDeviceId(user, oldestDevice.getDeviceId());
                userDeviceRepository.delete(oldestDevice);
            }
        }

        UserDeviceEntity userDevice = userDeviceRepository.findByUserIdAndDeviceId(user.getId(), deviceId)
                .orElse(UserDeviceEntity.builder()
                        .userId(user.getId())
                        .deviceId(deviceId)
                        .build());
        
        userDevice.setClientType(clientType);
        userDevice.setLastRole(activeRole);
        
        userDeviceRepository.save(userDevice);
    }

    @Override
    public void revokeDevice(UserEntity user, String deviceId) {
        refreshTokenRepository.deleteByUserAndDeviceId(user, deviceId);
        userDeviceRepository.deleteByUserIdAndDeviceId(user.getId(), deviceId);
    }
}