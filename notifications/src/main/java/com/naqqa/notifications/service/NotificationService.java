package com.naqqa.notifications.service;

import com.naqqa.commons.web.Pagination;
import com.naqqa.notifications.dto.Notification;
import com.naqqa.notifications.dto.NotificationsPage;
import com.naqqa.notifications.spi.NotificationSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class NotificationService {

    private static final int DEFAULT_PAGE_SIZE = 30;

    private final NotificationSource source;
    private final Set<String> readIds = ConcurrentHashMap.newKeySet();
    private final Set<String> deletedIds = ConcurrentHashMap.newKeySet();

    public NotificationService(NotificationSource source) {
        this.source = source;
    }

    private List<Notification> current() {
        List<Notification> all = new ArrayList<>();
        for (Notification n : source.all()) {
            if (deletedIds.contains(n.id())) {
                continue;
            }
            all.add(readIds.contains(n.id()) ? n.withSeen(true) : n);
        }
        all.sort(Comparator.comparing((Notification n) -> Instant.parse(n.createdAt())).reversed());
        return all;
    }

    public NotificationsPage list(int page, int size) {
        List<Notification> all = current();
        Pagination.Window window = Pagination.window(all.size(), page, size, DEFAULT_PAGE_SIZE);
        List<Map<String, Object>> slice = new ArrayList<>();
        for (Notification n : Pagination.slice(all, page, size, DEFAULT_PAGE_SIZE)) {
            slice.add(n.toMap());
        }
        long unread = all.stream().filter(n -> !n.seen()).count();
        return new NotificationsPage(slice, all.size(), window.totalPages(), window.page(), unread);
    }

    public void markRead(String id) {
        readIds.add(id);
    }

    public void markAllRead() {
        for (Notification n : source.all()) {
            readIds.add(n.id());
        }
    }

    public void delete(String id) {
        deletedIds.add(id);
    }
}
