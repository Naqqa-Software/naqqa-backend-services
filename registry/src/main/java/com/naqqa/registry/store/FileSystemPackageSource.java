package com.naqqa.registry.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.naqqa.registry.core.PackageNames;
import com.naqqa.registry.core.SemVer;
import com.naqqa.registry.domain.PackageCatalog;
import com.naqqa.registry.domain.PackageEntry;
import com.naqqa.registry.spi.PackageSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

public class FileSystemPackageSource implements PackageSource {

    public static final String DIST_TAGS_FILE = "dist-tags.json";
    static final Pattern TAG = Pattern.compile("^[a-z][a-z0-9._-]{0,63}$");
    static final List<String> DEPENDENCY_FIELDS = List.of("dependencies", "peerDependencies", "optionalDependencies");
    static final long MAX_MANIFEST_BYTES = 1_048_576;

    private static final Logger log = LoggerFactory.getLogger(FileSystemPackageSource.class);
    private static final LinkOption[] NO_FOLLOW = {LinkOption.NOFOLLOW_LINKS};

    private final Path root;
    private final Duration refresh;
    private final Clock clock;
    private final ObjectMapper json;
    private volatile PackageCatalog cached;
    private volatile Instant loadedAt;

    public FileSystemPackageSource(Path root, Duration refresh, Clock clock, ObjectMapper json) {
        this.root = root.toAbsolutePath().normalize();
        this.refresh = refresh == null ? Duration.ZERO : refresh;
        this.clock = clock;
        this.json = json;
    }

    public Path root() {
        return root;
    }

    @Override
    public PackageCatalog catalog() {
        PackageCatalog current = cached;
        if (current != null && clock.instant().isBefore(loadedAt.plus(refresh))) {
            return current;
        }
        synchronized (this) {
            if (cached != null && clock.instant().isBefore(loadedAt.plus(refresh))) {
                return cached;
            }
            PackageCatalog scanned = scan();
            loadedAt = clock.instant();
            cached = scanned;
            return scanned;
        }
    }

    @Override
    public Optional<Resource> tarball(String name, String version) {
        if (!PackageNames.isValid(name)) {
            return Optional.empty();
        }
        Optional<PackageEntry> entry = catalog().find(name);
        if (entry.isEmpty() || !entry.get().versions().containsKey(version)) {
            return Optional.empty();
        }
        Path file = packageDir(name).resolve(PackageNames.tarballFileName(name, version)).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file, NO_FOLLOW)) {
            return Optional.empty();
        }
        return Optional.of(new FileSystemResource(file));
    }

    Path packageDir(String name) {
        Path dir = root;
        for (String segment : name.split("/")) {
            dir = dir.resolve(segment);
        }
        return dir;
    }

    private PackageCatalog scan() {
        if (!Files.isDirectory(root, NO_FOLLOW)) {
            log.warn("naqqa-registry storage directory {} does not exist: the registry serves no packages.", root);
            return PackageCatalog.EMPTY;
        }
        Map<String, PackageEntry> packages = new HashMap<>();
        for (Path dir : children(root)) {
            String dirName = dir.getFileName().toString();
            if (!Files.isDirectory(dir, NO_FOLLOW)) {
                continue;
            }
            if (dirName.startsWith("@")) {
                if (!PackageNames.isSegment(dirName.substring(1))) {
                    continue;
                }
                for (Path child : children(dir)) {
                    String name = dirName + "/" + child.getFileName();
                    if (Files.isDirectory(child, NO_FOLLOW) && PackageNames.isValid(name)) {
                        readPackage(name, child).ifPresent(entry -> packages.put(entry.name(), entry));
                    }
                }
            } else if (PackageNames.isValid(dirName)) {
                readPackage(dirName, dir).ifPresent(entry -> packages.put(entry.name(), entry));
            }
        }
        return new PackageCatalog(packages);
    }

    private Optional<PackageEntry> readPackage(String name, Path dir) {
        Map<String, ObjectNode> versions = new TreeMap<>();
        Map<String, Instant> published = new HashMap<>();
        Set<String> dependencies = new LinkedHashSet<>();
        for (Path file : children(dir)) {
            String fileName = file.getFileName().toString();
            if (!fileName.endsWith(".json") || fileName.equals(DIST_TAGS_FILE) || !Files.isRegularFile(file, NO_FOLLOW)) {
                continue;
            }
            String version = fileName.substring(0, fileName.length() - ".json".length());
            if (!SemVer.isValid(version) || version.contains("+")) {
                continue;
            }
            Path tarball = dir.resolve(PackageNames.tarballFileName(name, version));
            if (!Files.isRegularFile(tarball, NO_FOLLOW)) {
                log.warn("naqqa-registry skips {}@{}: tarball {} is missing.", name, version, tarball.getFileName());
                continue;
            }
            Optional<ObjectNode> manifest = readManifest(file);
            if (manifest.isEmpty() || !name.equals(manifest.get().path("name").asText())
                    || !version.equals(manifest.get().path("version").asText())) {
                log.warn("naqqa-registry skips {}@{}: the manifest is unreadable or its name/version does not match.", name, version);
                continue;
            }
            versions.put(version, manifest.get());
            published.put(version, lastModified(tarball));
            for (String field : DEPENDENCY_FIELDS) {
                JsonNode deps = manifest.get().path(field);
                if (deps.isObject()) {
                    deps.fieldNames().forEachRemaining(dependencies::add);
                }
            }
        }
        if (versions.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PackageEntry(name, versions, published, distTags(dir, versions), dependencies));
    }

    private Map<String, String> distTags(Path dir, Map<String, ObjectNode> versions) {
        Map<String, String> tags = new TreeMap<>();
        Path file = dir.resolve(DIST_TAGS_FILE);
        if (Files.isRegularFile(file, NO_FOLLOW)) {
            readManifest(file).ifPresent(node -> {
                Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    if (TAG.matcher(field.getKey()).matches() && field.getValue().isTextual()
                            && versions.containsKey(field.getValue().asText())) {
                        tags.put(field.getKey(), field.getValue().asText());
                    }
                }
            });
        }
        if (!tags.containsKey("latest")) {
            tags.put("latest", highest(versions.keySet()));
        }
        return tags;
    }

    static String highest(Iterable<String> versions) {
        List<SemVer> stable = new ArrayList<>();
        List<SemVer> all = new ArrayList<>();
        Map<SemVer, String> raw = new HashMap<>();
        for (String version : versions) {
            SemVer parsed = SemVer.parse(version).orElseThrow();
            all.add(parsed);
            raw.put(parsed, version);
            if (!parsed.isPreRelease()) {
                stable.add(parsed);
            }
        }
        List<SemVer> pool = stable.isEmpty() ? all : stable;
        return raw.get(pool.stream().max(Comparator.naturalOrder()).orElseThrow());
    }

    private Optional<ObjectNode> readManifest(Path file) {
        try {
            if (Files.size(file) > MAX_MANIFEST_BYTES) {
                return Optional.empty();
            }
            JsonNode node = json.readTree(file.toFile());
            return node != null && node.isObject() ? Optional.of((ObjectNode) node) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Instant lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file, NO_FOLLOW).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }

    private static List<Path> children(Path dir) {
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path path : stream) {
                result.add(path);
            }
        } catch (IOException e) {
            log.warn("naqqa-registry cannot list {}: {}", dir, e.getMessage());
        }
        result.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return result;
    }
}
