package com.naqqa.analytics.banners.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BannerImage {
    private String url;
    private int w;
    private int h;
    private long bytes;
    private String format;

    public BannerImage(String url, int w, int h) {
        this(url, w, h, 0, null);
    }
}
