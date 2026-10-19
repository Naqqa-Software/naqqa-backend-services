package com.naqqa.auditlogs.dto;

public enum UserLogType {
    COMMENT_ADDED(UserLogDomain.COMMENT),
    COMMENT_REPLY(UserLogDomain.COMMENT),
    TASK_ASSIGNED(UserLogDomain.TASK),
    TASK_COMMENTED(UserLogDomain.TASK),
    BUILDER_PUBLISHED(UserLogDomain.BUILDER),
    ENTITY_CREATED(UserLogDomain.ENTITY),
    ENTITY_UPDATED(UserLogDomain.ENTITY),
    ENTITY_DELETED(UserLogDomain.ENTITY),
    ENTITY_PUBLISHED(UserLogDomain.ENTITY),
    OFFER_STATUS_CHANGED(UserLogDomain.OFFER),
    ROLE_CREATED(UserLogDomain.SECURITY),
    ROLE_UPDATED(UserLogDomain.SECURITY),
    ROLE_DELETED(UserLogDomain.SECURITY),
    PERMISSION_CREATED(UserLogDomain.SECURITY),
    PERMISSION_DELETED(UserLogDomain.SECURITY),
    USER_CREATED(UserLogDomain.SECURITY),
    USER_DELETED(UserLogDomain.SECURITY),
    USER_ENABLED(UserLogDomain.SECURITY),
    USER_DISABLED(UserLogDomain.SECURITY),
    USER_ROLES_CHANGED(UserLogDomain.SECURITY),
    USER_PERMISSIONS_CHANGED(UserLogDomain.SECURITY),
    SHIELD_SITE_CREATED(UserLogDomain.SHIELD),
    SHIELD_SITE_UPDATED(UserLogDomain.SHIELD),
    SHIELD_MODE_CHANGED(UserLogDomain.SHIELD),
    SHIELD_KEY_CREATED(UserLogDomain.SHIELD),
    SHIELD_KEY_ROTATED(UserLogDomain.SHIELD),
    SHIELD_KEY_REVOKED(UserLogDomain.SHIELD),
    SHIELD_SITE_DELETED(UserLogDomain.SHIELD),
    SHIELD_EVIDENCE_EXPORTED(UserLogDomain.SHIELD);

    private final UserLogDomain domain;

    UserLogType(UserLogDomain domain) {
        this.domain = domain;
    }

    public UserLogDomain getDomain() {
        return domain;
    }
}
