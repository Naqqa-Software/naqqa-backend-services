package com.naqqa.registry.domain;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

public record PackageEntry(
        String name,
        Map<String, ObjectNode> versions,
        Map<String, Instant> published,
        Map<String, String> distTags,
        Set<String> dependencies) {

    public PackageEntry {
        versions = Map.copyOf(versions);
        published = Map.copyOf(published);
        distTags = Map.copyOf(distTags);
        dependencies = Set.copyOf(dependencies);
    }

    public String latest() {
        return distTags.get("latest");
    }

    public ObjectNode manifest(String version) {
        ObjectNode manifest = versions.get(version);
        return manifest == null ? null : manifest.deepCopy();
    }
}
