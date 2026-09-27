package com.naqqa.elasticsearch.security.authc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class FileRealm implements Realm {

    private final String name;
    private final Map<String, String> userHashes;
    private final Map<String, List<String>> userRoles;

    public FileRealm(String name, Map<String, String> userHashes, Map<String, List<String>> userRoles) {
        this.name = name;
        this.userHashes = userHashes;
        this.userRoles = userRoles;
    }

    public static FileRealm loadFromFiles(String name, Path usersFile, Path usersRolesFile) throws IOException {
        Map<String, String> userHashes = new HashMap<>();
        for (String line : Files.readAllLines(usersFile, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int idx = trimmed.indexOf(':');
            if (idx < 0) {
                continue;
            }
            userHashes.put(trimmed.substring(0, idx), trimmed.substring(idx + 1));
        }
        Map<String, List<String>> userRoles = new HashMap<>();
        for (String line : Files.readAllLines(usersRolesFile, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int idx = trimmed.indexOf(':');
            if (idx < 0) {
                continue;
            }
            String role = trimmed.substring(0, idx);
            String[] usernames = trimmed.substring(idx + 1).split(",");
            for (String username : usernames) {
                String trimmedUsername = username.trim();
                if (trimmedUsername.isEmpty()) {
                    continue;
                }
                userRoles.computeIfAbsent(trimmedUsername, k -> new ArrayList<>()).add(role);
            }
        }
        return new FileRealm(name, userHashes, userRoles);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public AuthenticationResult authenticate(AuthenticationToken token) {
        if (!(token instanceof UsernamePasswordToken upToken)) {
            return AuthenticationResult.notHandled();
        }
        String hash = userHashes.get(upToken.username());
        if (hash == null) {
            return AuthenticationResult.notHandled();
        }
        if (!PasswordHashers.verify(upToken.password(), hash)) {
            return AuthenticationResult.unsuccessful("failed to authenticate user [" + upToken.username() + "]");
        }
        List<String> roles = userRoles.getOrDefault(upToken.username(), List.of());
        return AuthenticationResult.success(new User(upToken.username(), roles), name);
    }
}
