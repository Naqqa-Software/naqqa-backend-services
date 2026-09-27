package com.naqqa.elasticsearch.cluster.discovery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class FileBasedSeedHostsProvider implements SeedHostsProvider {

    private final Path file;

    public FileBasedSeedHostsProvider(Path file) {
        this.file = file;
    }

    @Override
    public List<String> getSeedAddresses() {
        List<String> addresses = new ArrayList<>();
        if (!Files.exists(file)) {
            return addresses;
        }
        try {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                addresses.add(trimmed);
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return addresses;
    }
}
