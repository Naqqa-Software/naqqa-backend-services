package com.naqqa.commons.web;

import java.util.List;

public record PageResponse<T>(List<T> items, long total, long totalItems, int totalPages, int currentPage, int size) {

    public static <T> PageResponse<T> fromAll(List<T> all, int page, int size, int defaultSize) {
        Pagination.Window window = Pagination.window(all.size(), page, size, defaultSize);
        List<T> slice = Pagination.slice(all, page, size, defaultSize);
        return new PageResponse<>(slice, all.size(), all.size(), window.totalPages(), window.page(), window.size());
    }
}
