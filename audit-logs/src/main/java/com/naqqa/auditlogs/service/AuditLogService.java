package com.naqqa.auditlogs.service;

import com.naqqa.auditlogs.dto.UserLog;
import com.naqqa.auditlogs.dto.UserLogsPage;
import com.naqqa.auditlogs.spi.AuditLogSource;

import java.util.ArrayList;
import java.util.List;

public class AuditLogService {

    private final AuditLogSource source;

    public AuditLogService(AuditLogSource source) {
        this.source = source;
    }

    public UserLogsPage list(int page, int size, String domain, String type) {
        int safeSize = size <= 0 ? 20 : size;
        int safePage = Math.max(0, page);
        List<UserLog> all = new ArrayList<>();
        for (UserLog log : source.all()) {
            if (domain != null && !domain.isBlank() && !log.domain().name().equalsIgnoreCase(domain)) {
                continue;
            }
            if (type != null && !type.isBlank() && !log.type().name().equalsIgnoreCase(type)) {
                continue;
            }
            all.add(log);
        }
        int start = safePage * safeSize;
        List<UserLog> slice = new ArrayList<>();
        for (int i = start; i < Math.min(start + safeSize, all.size()); i++) {
            slice.add(all.get(i));
        }
        int totalPages = Math.max(1, (int) Math.ceil(all.size() / (double) safeSize));
        return new UserLogsPage(slice, all.size(), all.size(), totalPages, safePage, safePage, safeSize);
    }
}
