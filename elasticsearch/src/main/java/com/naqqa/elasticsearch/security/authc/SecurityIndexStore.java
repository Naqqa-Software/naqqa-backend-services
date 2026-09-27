package com.naqqa.elasticsearch.security.authc;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

public interface SecurityIndexStore {

    Optional<Map<String, Object>> getUser(String username);

    void putUser(String username, Map<String, Object> userDocument);

    void removeUser(String username);

    Collection<String> listUsernames();
}
