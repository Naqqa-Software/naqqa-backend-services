package com.naqqa.elasticsearch.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class AnalysisContext {

    public static final AnalysisContext DEFAULT = new AnalysisContext(null);

    private final Path configDir;

    public AnalysisContext(Path configDir) {
        this.configDir = configDir;
    }

    public Path configDir() {
        return configDir;
    }

    public Path resolve(String path) {
        Path p = Path.of(path);
        if (p.isAbsolute() || configDir == null) {
            return p;
        }
        return configDir.resolve(p).normalize();
    }

    public List<String> readLines(String path, boolean removeComments) {
        Path p = resolve(path);
        List<String> lines;
        try {
            lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("IOException while reading " + path + ": " + p, e);
        }
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            if (!line.isEmpty() && line.charAt(0) == (char) 0xFEFF) {
                line = line.substring(1);
            }
            if (removeComments && line.startsWith("#")) {
                continue;
            }
            String trimmed = removeComments ? line.trim() : line;
            if (removeComments && trimmed.isEmpty()) {
                continue;
            }
            out.add(trimmed);
        }
        return out;
    }

    public List<String> getWordList(AnalysisSettings settings, String key) {
        return getWordList(settings, key, key + "_path");
    }

    public List<String> getWordList(AnalysisSettings settings, String key, String pathKey) {
        String path = settings.getString(pathKey);
        if (path != null) {
            return readLines(path, true);
        }
        return settings.getList(key);
    }
}
