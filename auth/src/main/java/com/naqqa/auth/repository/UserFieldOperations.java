package com.naqqa.auth.repository;

import java.util.Map;

public interface UserFieldOperations {

    boolean replacePassword(Long userId, String verifiedHash, String newHash);

    void updateFields(Long userId, Map<String, Object> fields);

    void addReference(Long userId, String field, Object value);

    void removeReference(Long userId, String field, Object value);
}
