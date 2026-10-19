package com.naqqa.auditlogs.dto;

import java.util.Map;

public record UserLog(String id,
                      String userId,
                      String userName,
                      UserLogType type,
                      UserLogDomain domain,
                      Map<String, Object> body,
                      String createdAt) {
}
