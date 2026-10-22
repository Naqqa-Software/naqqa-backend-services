package com.naqqa.registry.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.naqqa.registry.core.PackageNames;
import com.naqqa.registry.domain.PackageEntry;

import java.time.Instant;
import java.util.Comparator;

public class NpmPackuments {

    private final ObjectMapper json;

    public NpmPackuments(ObjectMapper json) {
        this.json = json;
    }

    public ObjectNode packument(PackageEntry entry, String npmBaseUrl) {
        String name = entry.name();
        ObjectNode root = json.createObjectNode();
        root.put("_id", name);
        root.put("name", name);
        ObjectNode latest = entry.versions().get(entry.latest());
        if (latest != null && latest.hasNonNull("description")) {
            root.set("description", latest.get("description"));
        }
        ObjectNode tags = root.putObject("dist-tags");
        entry.distTags().forEach(tags::put);
        ObjectNode versions = root.putObject("versions");
        ObjectNode time = json.createObjectNode();
        for (String version : RegistryService.sortedVersions(entry)) {
            versions.set(version, version(entry, version, npmBaseUrl));
        }
        Instant created = entry.published().values().stream().min(Comparator.naturalOrder()).orElse(Instant.EPOCH);
        Instant modified = entry.published().values().stream().max(Comparator.naturalOrder()).orElse(Instant.EPOCH);
        time.put("created", created.toString());
        time.put("modified", modified.toString());
        for (String version : RegistryService.sortedVersions(entry)) {
            time.put(version, entry.published().getOrDefault(version, Instant.EPOCH).toString());
        }
        root.set("time", time);
        return root;
    }

    ObjectNode version(PackageEntry entry, String version, String npmBaseUrl) {
        ObjectNode manifest = entry.manifest(version);
        manifest.put("_id", entry.name() + "@" + version);
        JsonNode storedDist = manifest.path("dist");
        ObjectNode dist = json.createObjectNode();
        if (storedDist.isObject()) {
            copyText(storedDist, dist, "shasum");
            copyText(storedDist, dist, "integrity");
            if (storedDist.has("fileCount")) {
                dist.set("fileCount", storedDist.get("fileCount"));
            }
            if (storedDist.has("unpackedSize")) {
                dist.set("unpackedSize", storedDist.get("unpackedSize"));
            }
        }
        dist.put("tarball", tarballUrl(npmBaseUrl, entry.name(), version));
        manifest.set("dist", dist);
        return manifest;
    }

    public static String tarballUrl(String npmBaseUrl, String name, String version) {
        String base = npmBaseUrl.endsWith("/") ? npmBaseUrl.substring(0, npmBaseUrl.length() - 1) : npmBaseUrl;
        return base + "/" + name + "/-/" + PackageNames.tarballFileName(name, version);
    }

    private static void copyText(JsonNode from, ObjectNode to, String field) {
        if (from.path(field).isTextual()) {
            to.put(field, from.get(field).asText());
        }
    }
}
