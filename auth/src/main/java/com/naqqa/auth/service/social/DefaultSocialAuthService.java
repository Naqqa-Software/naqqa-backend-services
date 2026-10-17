package com.naqqa.auth.service.social;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.repository.RoleRepository;
import com.naqqa.auth.repository.UserRepository;
import com.naqqa.auth.roles.RoleProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
@RequiredArgsConstructor
public class DefaultSocialAuthService implements SocialAuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RoleProvider roleProvider;

    @Override
    public UserEntity processUser(String registrationId, String socialId, String email, String name) {

        return userRepository.findByEmail(email)
                .map(user -> linkSocialProfile(user, registrationId, socialId))
                .orElseGet(() -> createUser(email, name, registrationId, socialId));
    }

    private UserEntity linkSocialProfile(UserEntity user, String registrationId, String socialId) {
        String field = null;
        switch (registrationId.toLowerCase()) {
            case "google":
                user.setGoogleId(socialId);
                field = "googleId";
                break;
            case "facebook":
                user.setFacebookId(socialId);
                field = "facebookId";
                break;
            case "apple":
                user.setAppleId(socialId);
                field = "appleId";
                break;
        }
        if (field != null) {
            userRepository.updateFields(user.getId(), Collections.singletonMap(field, socialId));
        }
        return user;
    }

    private UserEntity createUser(String email, String name, String registrationId, String socialId) {
        UserEntity user = new UserEntity();
        user.setEmail(email);
        user.setFullName(name);
        user.setPassword(null);
        user.setEnabled(true);

        RoleEntity defaultRole = roleRepository.findByName(roleProvider.getDefaultRole())
                .orElseThrow(() -> new RuntimeException("Default role not found"));
        user.getRoles().add(defaultRole);

        switch (registrationId.toLowerCase()) {
            case "google":
                user.setGoogleId(socialId);
                break;
            case "facebook":
                user.setFacebookId(socialId);
                break;
            case "apple":
                user.setAppleId(socialId);
                break;
        }

        return userRepository.save(user);
    }
}
