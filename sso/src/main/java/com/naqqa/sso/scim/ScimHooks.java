package com.naqqa.sso.scim;

public interface ScimHooks {
    ScimHooks NONE = new ScimHooks() {
    };

    default void validatePassword(String tenantId, String password) {
    }

    default void onPasswordSet(String tenantId, String userId, String password) {
    }

    default void onDeactivated(String tenantId, String userId) {
    }

    default void onDeleted(String tenantId, String userId) {
    }
}
