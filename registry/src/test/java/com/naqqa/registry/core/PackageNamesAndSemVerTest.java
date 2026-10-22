package com.naqqa.registry.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PackageNamesAndSemVerTest {

    @Test
    void acceptsNpmPackageNames() {
        assertThat(PackageNames.isValid("@naqqa/badge")).isTrue();
        assertThat(PackageNames.isValid("@naqqa/search-engine")).isTrue();
        assertThat(PackageNames.isValid("tslib")).isTrue();
        assertThat(PackageNames.isValid("a.b_c-d")).isTrue();
    }

    @Test
    void rejectsTraversalAndMalformedNames() {
        for (String bad : List.of("", "@naqqa", "@naqqa/", "@/x", "@naqqa/../x", "..", "@naqqa/..", "a/b", "@a/b/c",
                "Badge", "@naqqa/Badge", "-x", ".x", "x y", "@naqqa\\x", "x".repeat(215))) {
            assertThat(PackageNames.isValid(bad)).as(bad).isFalse();
        }
        assertThat(PackageNames.isValid(null)).isFalse();
    }

    @Test
    void patternsAreExactNamesOrScopeWildcards() {
        assertThat(PackageNames.isValidPattern("@naqqa/*")).isTrue();
        assertThat(PackageNames.isValidPattern("@naqqa/badge")).isTrue();
        assertThat(PackageNames.isValidPattern("*")).isFalse();
        assertThat(PackageNames.isValidPattern("@naqqa/b*")).isFalse();
        assertThat(PackageNames.isValidPattern("@*/x")).isFalse();
        assertThat(PackageNames.matches("@naqqa/*", "@naqqa/badge")).isTrue();
        assertThat(PackageNames.matches("@naqqa/*", "@naqqa-evil/badge")).isFalse();
        assertThat(PackageNames.matches("@naqqa/*", "@naqqa")).isFalse();
        assertThat(PackageNames.matches("@naqqa/badge", "@naqqa/badge")).isTrue();
        assertThat(PackageNames.matches("@naqqa/badge", "@naqqa/badges")).isFalse();
    }

    @Test
    void tarballNamesFollowTheNpmConvention() {
        assertThat(PackageNames.tarballFileName("@naqqa/badge", "1.2.3")).isEqualTo("badge-1.2.3.tgz");
        assertThat(PackageNames.tarballFileName("tslib", "2.0.0-rc.1")).isEqualTo("tslib-2.0.0-rc.1.tgz");
    }

    @Test
    void semverPrecedenceFollowsTheSpec() {
        List<String> ordered = List.of("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2",
                "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.2.0", "1.10.0", "2.0.0");
        for (int i = 0; i + 1 < ordered.size(); i++) {
            SemVer a = SemVer.parse(ordered.get(i)).orElseThrow();
            SemVer b = SemVer.parse(ordered.get(i + 1)).orElseThrow();
            assertThat(a.compareTo(b)).as(ordered.get(i) + " < " + ordered.get(i + 1)).isNegative();
            assertThat(b.compareTo(a)).isPositive();
        }
        assertThat(SemVer.parse("1.0.0+build.1").orElseThrow()).isEqualTo(SemVer.parse("1.0.0").orElseThrow());
    }

    @Test
    void rejectsInvalidVersions() {
        for (String bad : List.of("1", "1.0", "01.0.0", "1.0.0-", "v1.0.0", "1.0.0.0", "../1.0.0", "1.0.0-a..b", "")) {
            assertThat(SemVer.isValid(bad)).as(bad).isFalse();
        }
        assertThat(SemVer.isValid(null)).isFalse();
    }
}
