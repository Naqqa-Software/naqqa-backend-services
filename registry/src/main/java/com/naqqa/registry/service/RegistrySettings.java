package com.naqqa.registry.service;

import com.naqqa.registry.core.PackageNames;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public record RegistrySettings(Map<String, List<String>> packageSets, Duration defaultTtl, Duration maxTtl,
                               boolean includeDependencies) {

    static final Pattern SET_NAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");

    public RegistrySettings {
        Map<String, List<String>> sets = new LinkedHashMap<>();
        if (packageSets != null) {
            packageSets.forEach((name, patterns) -> {
                if (!SET_NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("package set name '" + name + "' must match " + SET_NAME.pattern());
                }
                List<String> list = patterns == null ? List.of() : patterns.stream().map(String::trim).toList();
                for (String pattern : list) {
                    if (!PackageNames.isValidPattern(pattern)) {
                        throw new IllegalArgumentException("package set '" + name + "' has an invalid package pattern '" + pattern + "'");
                    }
                }
                sets.put(name, list);
            });
        }
        packageSets = Collections.unmodifiableMap(sets);
        if (defaultTtl == null || defaultTtl.isNegative() || defaultTtl.isZero()) {
            throw new IllegalArgumentException("default token TTL must be positive");
        }
        if (maxTtl == null || maxTtl.compareTo(defaultTtl) < 0) {
            throw new IllegalArgumentException("max token TTL must be at least the default token TTL");
        }
    }

    public static RegistrySettings defaults() {
        return new RegistrySettings(Map.of(), Duration.ofDays(365), Duration.ofDays(730), true);
    }
}
