package com.naqqa.analytics.banners.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BannerDestination {

    public enum Type {
        INTERNAL,
        EXTERNAL
    }

    private Type type = Type.INTERNAL;
    private String path;
    private String url;

    public boolean external() {
        return type == Type.EXTERNAL;
    }
}
