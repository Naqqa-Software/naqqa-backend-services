package com.naqqa.auth.service.social;

import com.naqqa.auth.entity.auth.UserEntity;
import org.springframework.security.oauth2.core.user.OAuth2User;

public interface SocialAuthService {
    UserEntity processUser(String registrationId, OAuth2User oauth2User);
}
