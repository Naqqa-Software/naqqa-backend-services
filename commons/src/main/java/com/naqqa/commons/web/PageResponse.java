package com.naqqa.commons.web;

import java.util.List;

/**
 * Canonical paging envelope for Naqqa list endpoints. New endpoints return this shape directly;
 * existing modules that ship a differently named envelope (for example {@code NotificationsPage}
 * with its {@code notifications} field, or {@code UserLogsPage}) keep their wire contract but
 * compute the page via {@link Pagination}. The duplicate {@code total} and {@code totalItems}
 * fields carry the same value and cover both aliases the naqqa-os dev-data uses.
 *
 * @param items       the slice for the current page
 * @param total       total item count across all pages (alias of {@code totalItems})
 * @param totalItems  total item count across all pages
 * @param totalPages  number of pages (at least 1)
 * @param currentPage zero-based current page index
 * @param size        page size
 */
public record PageResponse<T>(List<T> items, long total, long totalItems, int totalPages, int currentPage, int size) {

    public static <T> PageResponse<T> fromAll(List<T> all, int page, int size, int defaultSize) {
        Pagination.Window window = Pagination.window(all.size(), page, size, defaultSize);
        List<T> slice = Pagination.slice(all, page, size, defaultSize);
        return new PageResponse<>(slice, all.size(), all.size(), window.totalPages(), window.page(), window.size());
    }
}
