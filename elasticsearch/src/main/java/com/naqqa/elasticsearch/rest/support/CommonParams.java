package com.naqqa.elasticsearch.rest.support;

import java.util.LinkedHashSet;
import java.util.Set;

public final class CommonParams {

    private CommonParams() {
    }

    public static final Set<String> NONE = Set.of();

    public static final Set<String> INDICES_OPTIONS = Set.of("expand_wildcards", "ignore_unavailable", "allow_no_indices");

    public static final Set<String> MASTER_TIMEOUT = Set.of("master_timeout", "timeout");

    public static final Set<String> WAIT_ACTIVE_SHARDS = Set.of("wait_for_active_shards");

    public static final Set<String> LOCAL = Set.of("local");

    public static final Set<String> FLAT_SETTINGS = Set.of("flat_settings");

    public static final Set<String> INCLUDE_DEFAULTS = Set.of("include_defaults");

    @SafeVarargs
    public static Set<String> union(Set<String>... sets) {
        Set<String> result = new LinkedHashSet<>();
        for (Set<String> set : sets) {
            result.addAll(set);
        }
        return Set.copyOf(result);
    }

    public static Set<String> of(String... names) {
        return Set.of(names);
    }
}
