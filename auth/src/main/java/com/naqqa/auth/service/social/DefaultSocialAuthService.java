package com.naqqa.auth.service.social;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.repository.RoleRepository;
import com.naqqa.auth.repository.UserRepository;
import com.naqqa.auth.roles.RoleProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DefaultSocialAuthService implements SocialAuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RoleProvider roleProvider;

    @Override
    public UserEntity processUser(String registrationId, OAuth2User oauth2User) {
        String email = oauth2User.getAttribute("email");
        String name = oauth2User.getAttribute("name");
        String socialId = oauth2User.getName();

        return userRepository.findByEmail(email)
                .map(user -> linkSocialProfile(user, registrationId, socialId))
                .orElseGet(() -> createUser(email, name, registrationId, socialId));
    }

    private UserEntity linkSocialProfile(UserEntity user, String registrationId, String socialId) {
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

    private UserEntity createUser(String email, String name, String registrationId, String socialId) {
        UserEntity user = new UserEntity();
        user.setEmail(email);
        user.setFullName(name);
        // No local password: the provider is the only credential until the user sets one.
        // (Storing a random UUID here left a plaintext, password-shaped value in the column and
        // made the account indistinguishable from a password account to every other caller.)
        user.setPassword(UserEntity.NO_PASSWORD);
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
