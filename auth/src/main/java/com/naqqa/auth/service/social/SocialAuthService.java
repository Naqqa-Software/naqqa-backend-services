package com.naqqa.auth.service.social;

import com.naqqa.auth.entity.auth.UserEntity;

public interface SocialAuthService {
    UserEntity processUser(String registrationId, String socialId, String email, String name);
}
