package com.naqqa.commons.web;

import java.util.ArrayList;
import java.util.List;

public final class Pagination {

    private Pagination() {
    }

    public record Window(int page, int size, int totalPages, int start, int end) {
    }

    public static Window window(int totalItems, int page, int size, int defaultSize) {
        int total = Math.max(0, totalItems);
        int safeSize = size <= 0 ? defaultSize : size;
        int safePage = Math.max(0, page);
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) safeSize));
        int start = Math.min(safePage * safeSize, total);
        int end = Math.min(start + safeSize, total);
        return new Window(safePage, safeSize, totalPages, start, end);
    }

    public static <T> List<T> slice(List<T> all, int page, int size, int defaultSize) {
        Window window = window(all.size(), page, size, defaultSize);
        List<T> out = new ArrayList<>();
        for (int i = window.start(); i < window.end(); i++) {
            out.add(all.get(i));
        }
        return out;
    }
}
