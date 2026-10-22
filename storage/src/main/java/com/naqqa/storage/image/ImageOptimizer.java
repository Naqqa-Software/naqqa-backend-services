package com.naqqa.storage.image;

import java.io.IOException;
import java.util.List;

public interface ImageOptimizer {

    record Variant(int width, int height, byte[] bytes, boolean fullSize, String extension) {
    }

    record Result(int width, int height, List<Variant> variants) {
    }

    boolean isEnabled();

    boolean isOptimizable(byte[] bytes);

    Result optimize(byte[] bytes, long waitSeconds) throws IOException, InterruptedException;
}
