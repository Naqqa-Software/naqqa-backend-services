package com.naqqa.analytics.banners.spi;

public interface BannerAssetStorage {

    BannerAssetStorage NONE = new BannerAssetStorage() {
        @Override
        public String store(String filename, String contentType, byte[] bytes) {
            throw new UnsupportedOperationException("No BannerAssetStorage bean configured");
        }
    };

    String store(String filename, String contentType, byte[] bytes);

    default void delete(String url) {
    }

    default boolean available() {
        return this != NONE;
    }
}
