package com.naqqa.elasticsearch.node.support;

import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.cluster.state.IndexMetadata;
import com.naqqa.elasticsearch.cluster.state.Metadata;
import com.naqqa.elasticsearch.common.regex.Regex;
import com.naqqa.elasticsearch.rest.support.IndexNotFoundException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class IndexResolver {

    private final Function<String, List<String>> dataStreamResolver;
    private final Function<String, Set<String>> dataStreamPatternResolver;

    public IndexResolver(Function<String, List<String>> dataStreamResolver, Function<String, Set<String>> dataStreamPatternResolver) {
        this.dataStreamResolver = dataStreamResolver;
        this.dataStreamPatternResolver = dataStreamPatternResolver;
    }

    public List<String> resolve(ClusterState state, List<String> expressions, boolean includeClosed, boolean ignoreUnavailable) {
        Metadata metadata = state.getMetadata();
        Map<String, IndexMetadata> all = metadata.getIndices();
        List<String> effective = expressions == null || expressions.isEmpty() ? List.of("_all") : expressions;
        Set<String> result = new LinkedHashSet<>();
        for (String raw : effective) {
            for (String expr : raw.split(",")) {
                expr = expr.trim();
                if (expr.isEmpty()) {
                    continue;
                }
                boolean exclude = expr.startsWith("-") && !result.isEmpty();
                String name = exclude ? expr.substring(1) : expr;
                Set<String> matched = new LinkedHashSet<>();
                if ("_all".equals(name) || "*".equals(name)) {
                    matched.addAll(all.keySet());
                } else if (Regex.isSimpleMatchPattern(name)) {
                    for (String index : all.keySet()) {
                        if (Regex.simpleMatch(name, index)) {
                            matched.add(index);
                        }
                    }
                    for (IndexMetadata imd : all.values()) {
                        for (String alias : imd.getAliases().keySet()) {
                            if (Regex.simpleMatch(name, alias)) {
                                matched.add(imd.getIndex());
                            }
                        }
                    }
                    matched.addAll(dataStreamPatternResolver.apply(name));
                } else if (all.containsKey(name)) {
                    matched.add(name);
                } else {
                    Set<String> aliased = metadata.resolveIndicesForAlias(name);
                    if (!aliased.isEmpty()) {
                        matched.addAll(aliased);
                    } else {
                        List<String> backing = dataStreamResolver.apply(name);
                        if (backing != null && !backing.isEmpty()) {
                            matched.addAll(backing);
                        } else if (!ignoreUnavailable && !exclude) {
                            throw new IndexNotFoundException(name);
                        }
                    }
                }
                if (exclude) {
                    result.removeAll(matched);
                } else {
                    result.addAll(matched);
                }
            }
        }
        List<String> out = new ArrayList<>();
        for (String index : result) {
            IndexMetadata imd = all.get(index);
            if (imd == null) {
                continue;
            }
            if (!includeClosed && imd.getState() == IndexMetadata.State.CLOSE) {
                continue;
            }
            out.add(index);
        }
        return out;
    }

    public static boolean isConcreteMissing(ClusterState state, String name) {
        return state.getMetadata().index(name) == null && state.getMetadata().resolveIndicesForAlias(name).isEmpty();
    }
}
