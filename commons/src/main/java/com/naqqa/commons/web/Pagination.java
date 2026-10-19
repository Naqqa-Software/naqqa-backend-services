package com.naqqa.commons.web;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic, zero-based list pagination shared by the Naqqa modules. It factors out the
 * copy-pasted {@code list(page, size)} arithmetic in the notification and audit-log services:
 * {@code size <= 0} falls back to {@code defaultSize}, {@code page} is clamped to {@code >= 0}, and
 * {@code totalPages} is always at least 1 so an empty list still reports a single page. The computed
 * slice is identical to the hand-rolled loops it replaces.
 */
public final class Pagination {

    private Pagination() {
    }

    /** The resolved window: the sane page/size, total page count, and slice bounds. */
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
