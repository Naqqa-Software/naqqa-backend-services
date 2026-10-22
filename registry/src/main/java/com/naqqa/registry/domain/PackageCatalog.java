package com.naqqa.registry.domain;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

public record PackageCatalog(Map<String, PackageEntry> packages) {

    public static final PackageCatalog EMPTY = new PackageCatalog(Map.of());

    public PackageCatalog {
        packages = Collections.unmodifiableMap(new TreeMap<>(packages));
    }

    public Optional<PackageEntry> find(String name) {
        return Optional.ofNullable(packages.get(name));
    }

    public Set<String> names() {
        return packages.keySet();
    }
}
