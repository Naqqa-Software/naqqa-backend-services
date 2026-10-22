package com.naqqa.notifications.dto;

import java.util.List;
import java.util.Map;

public record NotificationsPage(List<Map<String, Object>> notifications,
                                long totalItems,
                                int totalPages,
                                int currentPage,
                                long count) {
}
