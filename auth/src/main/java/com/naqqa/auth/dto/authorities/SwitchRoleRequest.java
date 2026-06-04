package com.naqqa.auth.dto.authorities;

public record SwitchRoleRequest(Long roleId, String deviceId, String clientType) {}
