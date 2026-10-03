package com.naqqa.analytics.banners.model;

public enum BannerPriority {
    PAID(3),
    INTERNAL(2),
    FALLBACK(1);

    private final int rank;

    BannerPriority(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }
}
