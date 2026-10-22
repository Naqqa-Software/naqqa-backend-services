package com.naqqa.auditlogs.spi;

import com.naqqa.auditlogs.dto.UserLog;
import com.naqqa.auditlogs.dto.UserLogType;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DevAuditLogSource implements AuditLogSource {

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    private static String minutesAgo(long m) {
        return Instant.now().minusSeconds(m * 60).toString();
    }

    private static String hoursAgo(long h) {
        return Instant.now().minusSeconds(h * 3600).toString();
    }

    private static String daysAgo(long d) {
        return Instant.now().minusSeconds(d * 86400).toString();
    }

    @Override
    public List<UserLog> all() {
        return List.of(
                new UserLog("log-1", "u-1001", "Catalin Rusu", UserLogType.COMMENT_ADDED, UserLogType.COMMENT_ADDED.getDomain(),
                        body("pageName", "Pricing", "text", "Can we bump the font size on the hero CTA?"), minutesAgo(12)),
                new UserLog("log-2", "u-1003", "Mihai Rusu", UserLogType.TASK_ASSIGNED, UserLogType.TASK_ASSIGNED.getDomain(),
                        body("taskTitle", "Fix SSR hydration mismatch on pricing page"), hoursAgo(1)),
                new UserLog("log-3", "u-1002", "Elena Pop", UserLogType.BUILDER_PUBLISHED, UserLogType.BUILDER_PUBLISHED.getDomain(),
                        body("projectName", "OMY Platform", "pages", 14), daysAgo(1)),
                new UserLog("log-4", "u-1001", "Catalin Rusu", UserLogType.ENTITY_UPDATED, UserLogType.ENTITY_UPDATED.getDomain(),
                        body("entityKey", "client:acme-srl"), daysAgo(1)),
                new UserLog("log-5", "u-1002", "Elena Pop", UserLogType.OFFER_STATUS_CHANGED, UserLogType.OFFER_STATUS_CHANGED.getDomain(),
                        body("offerName", "ACME SRL - Website revamp", "status", "SENT"), daysAgo(2)),
                new UserLog("log-6", "u-1001", "Catalin Rusu", UserLogType.ROLE_UPDATED, UserLogType.ROLE_UPDATED.getDomain(),
                        body("role", "PROJECT_MANAGER"), daysAgo(2)),
                new UserLog("log-7", "u-1003", "Mihai Rusu", UserLogType.USER_ROLES_CHANGED, UserLogType.USER_ROLES_CHANGED.getDomain(),
                        body("targetUserId", "u-1002", "roles", List.of("DEVELOPER", "REPORTER")), daysAgo(3)),
                new UserLog("log-8", "u-1001", "Catalin Rusu", UserLogType.SHIELD_SITE_CREATED, UserLogType.SHIELD_SITE_CREATED.getDomain(),
                        body("siteName", "OMY Platform"), daysAgo(3)),
                new UserLog("log-9", "u-1002", "Elena Pop", UserLogType.SHIELD_KEY_ROTATED, UserLogType.SHIELD_KEY_ROTATED.getDomain(),
                        body("kind", "sk", "keyId", "key-shield-sk-2", "graceHours", 24), daysAgo(4)),
                new UserLog("log-10", "u-1003", "Mihai Rusu", UserLogType.USER_CREATED, UserLogType.USER_CREATED.getDomain(),
                        body("email", "new.hire@selectx.md"), daysAgo(5)),
                new UserLog("log-11", "u-1001", "Catalin Rusu", UserLogType.ENTITY_PUBLISHED, UserLogType.ENTITY_PUBLISHED.getDomain(),
                        body(), daysAgo(6)),
                new UserLog("log-12", "u-1002", "Elena Pop", UserLogType.PERMISSION_CREATED, UserLogType.PERMISSION_CREATED.getDomain(),
                        body("permission", "shield:evidence:export"), daysAgo(7))
        );
    }
}
