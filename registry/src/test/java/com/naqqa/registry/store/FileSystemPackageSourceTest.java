package com.naqqa.registry.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.registry.domain.PackageCatalog;
import com.naqqa.registry.domain.PackageEntry;
import com.naqqa.registry.support.MutableClock;
import com.naqqa.registry.support.StorageFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileSystemPackageSourceTest {

    @TempDir
    Path root;

    private StorageFixture storage;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        storage = new StorageFixture(root);
        clock = new MutableClock(Instant.parse("2026-10-19T10:00:00Z"));
    }

    private FileSystemPackageSource source(Duration refresh) {
        return new FileSystemPackageSource(root, refresh, clock, new ObjectMapper());
    }

    @Test
    void readsScopedAndUnscopedPackages() {
        storage.publish("@naqqa/badge", "0.0.1").publish("@naqqa/badge", "0.1.0", Map.of("@naqqa/icon", "^0.0.1"))
                .publish("plain", "1.0.0");
        PackageCatalog catalog = source(Duration.ZERO).catalog();
        assertThat(catalog.names()).containsExactly("@naqqa/badge", "plain");
        PackageEntry badge = catalog.find("@naqqa/badge").orElseThrow();
        assertThat(badge.versions()).containsOnlyKeys("0.0.1", "0.1.0");
        assertThat(badge.latest()).isEqualTo("0.1.0");
        assertThat(badge.dependencies()).containsExactly("@naqqa/icon");
    }

    @Test
    void latestIsTheHighestStableVersionUnlessDistTagsSayOtherwise() {
        storage.publish("@naqqa/a", "1.9.0").publish("@naqqa/a", "1.10.0").publish("@naqqa/a", "2.0.0-rc.1");
        assertThat(source(Duration.ZERO).catalog().find("@naqqa/a").orElseThrow().latest()).isEqualTo("1.10.0");
        storage.distTags("@naqqa/a", Map.of("latest", "1.9.0", "next", "2.0.0-rc.1", "ghost", "9.9.9", "BAD TAG", "1.9.0"));
        PackageEntry entry = source(Duration.ZERO).catalog().find("@naqqa/a").orElseThrow();
        assertThat(entry.distTags()).containsExactlyInAnyOrderEntriesOf(Map.of("latest", "1.9.0", "next", "2.0.0-rc.1"));
        storage.publish("@naqqa/pre", "1.0.0-beta.1").publish("@naqqa/pre", "1.0.0-beta.2");
        assertThat(source(Duration.ZERO).catalog().find("@naqqa/pre").orElseThrow().latest()).isEqualTo("1.0.0-beta.2");
    }

    @Test
    void skipsBrokenOrForeignEntries() {
        storage.publish("@naqqa/ok", "1.0.0")
                .write("@naqqa/no-tarball/1.0.0.json", "{\"name\":\"@naqqa/no-tarball\",\"version\":\"1.0.0\"}")
                .write("@naqqa/wrong-name/1.0.0.json", "{\"name\":\"@naqqa/other\",\"version\":\"1.0.0\"}")
                .write("@naqqa/wrong-name/wrong-name-1.0.0.tgz", "x")
                .write("@naqqa/garbage/1.0.0.json", "not json")
                .write("@naqqa/garbage/garbage-1.0.0.tgz", "x")
                .write("@naqqa/bad-version/latest.json", "{\"name\":\"@naqqa/bad-version\",\"version\":\"latest\"}")
                .write("@naqqa/bad-version/bad-version-latest.tgz", "x")
                .write("@Upper/x/1.0.0.json", "{\"name\":\"@Upper/x\",\"version\":\"1.0.0\"}")
                .write("@Upper/x/x-1.0.0.tgz", "x")
                .write("README.md", "not a package");
        assertThat(source(Duration.ZERO).catalog().names()).containsExactly("@naqqa/ok");
    }

    @Test
    void missingStorageDirectoryServesNothing() {
        FileSystemPackageSource source = new FileSystemPackageSource(root.resolve("missing"), Duration.ZERO, clock, new ObjectMapper());
        assertThat(source.catalog().names()).isEmpty();
        assertThat(source.tarball("@naqqa/badge", "1.0.0")).isEmpty();
    }

    @Test
    void tarballOnlyForKnownVersionsInsideTheRoot() throws Exception {
        storage.publish("@naqqa/badge", "1.0.0");
        FileSystemPackageSource source = source(Duration.ZERO);
        Resource resource = source.tarball("@naqqa/badge", "1.0.0").orElseThrow();
        assertThat(resource.getContentAsByteArray()).isEqualTo(StorageFixture.tarballBytes("@naqqa/badge", "1.0.0"));
        assertThat(source.tarball("@naqqa/badge", "2.0.0")).isEmpty();
        assertThat(source.tarball("@naqqa/../badge", "1.0.0")).isEmpty();
        assertThat(source.tarball("@naqqa/badge", "../../1.0.0")).isEmpty();
    }

    @Test
    void catalogIsCachedForTheRefreshInterval() {
        storage.publish("@naqqa/a", "1.0.0");
        FileSystemPackageSource source = source(Duration.ofSeconds(5));
        assertThat(source.catalog().names()).containsExactly("@naqqa/a");
        storage.publish("@naqqa/b", "1.0.0");
        assertThat(source.catalog().names()).containsExactly("@naqqa/a");
        clock.advance(Duration.ofSeconds(5));
        assertThat(source.catalog().names()).containsExactly("@naqqa/a", "@naqqa/b");
    }

    @Test
    void highestIgnoresPreReleasesWhenAStableExists() {
        assertThat(FileSystemPackageSource.highest(List.of("1.0.0", "1.1.0-rc.1", "0.9.0"))).isEqualTo("1.0.0");
        assertThat(FileSystemPackageSource.highest(List.of("1.1.0-rc.1", "1.1.0-rc.2"))).isEqualTo("1.1.0-rc.2");
    }
}
