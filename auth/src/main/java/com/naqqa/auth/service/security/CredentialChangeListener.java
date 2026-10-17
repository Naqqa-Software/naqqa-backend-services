package com.naqqa.auth.service.security;

import com.naqqa.auth.entity.auth.UserEntity;

public interface CredentialChangeListener {

    void onCredentialsChanged(UserEntity user, String reason);
}
