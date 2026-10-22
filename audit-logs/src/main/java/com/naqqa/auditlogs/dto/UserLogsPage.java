package com.naqqa.auditlogs.dto;

import java.util.List;

public record UserLogsPage(List<UserLog> logs,
                           long total,
                           long totalItems,
                           int totalPages,
                           int page,
                           int currentPage,
                           int size) {
}
