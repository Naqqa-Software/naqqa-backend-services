package com.naqqa.auditlogs.spi;

import com.naqqa.auditlogs.dto.UserLog;

import java.util.List;

public interface AuditLogSource {

    List<UserLog> all();
}
