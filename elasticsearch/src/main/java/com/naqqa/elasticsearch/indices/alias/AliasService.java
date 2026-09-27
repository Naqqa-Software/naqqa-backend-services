package com.naqqa.elasticsearch.indices.alias;

import com.naqqa.elasticsearch.common.exception.ElasticsearchException;
import com.naqqa.elasticsearch.common.exception.IndexNotFoundException;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AliasService {

    private final Object lock = new Object();
    private Map<String, Map<String, AliasMetadata>> aliasesByIndex = new LinkedHashMap<>();
    private final Set<String> knownIndices = new LinkedHashSet<>();

    public void registerIndex(String index) {
        synchronized (lock) {
            knownIndices.add(index);
            aliasesByIndex.putIfAbsent(index, new LinkedHashMap<>());
        }
    }

    public void unregisterIndex(String index) {
        synchronized (lock) {
            knownIndices.remove(index);
            aliasesByIndex.remove(index);
        }
    }

    public void applyActions(List<AliasAction> actions) {
        synchronized (lock) {
            Map<String, Map<String, AliasMetadata>> working = deepCopy(aliasesByIndex);
            for (AliasAction action : actions) {
                apply(working, action);
            }
            validateWriteIndexUniqueness(working);
            this.aliasesByIndex = working;
        }
    }

    private void apply(Map<String, Map<String, AliasMetadata>> working, AliasAction action) {
        switch (action.getType()) {
            case ADD -> {
                requireIndex(working, action.getIndex());
                Map<String, AliasMetadata> forIndex = working.get(action.getIndex());
                AliasMetadata metadata = new AliasMetadata(action.getAlias(), action.getIndex(), action.getFilter(),
                    action.getIndexRouting(), action.getSearchRouting(),
                    action.getWriteIndex() != null && action.getWriteIndex(), action.getWriteIndex() != null);
                forIndex.put(action.getAlias(), metadata);
            }
            case REMOVE -> {
                requireIndex(working, action.getIndex());
                Map<String, AliasMetadata> forIndex = working.get(action.getIndex());
                AliasMetadata removed = forIndex.remove(action.getAlias());
                if (removed == null && action.isMustExist()) {
                    throw new ElasticsearchException("alias [{}] missing for index [{}]", action.getAlias(), action.getIndex());
                }
            }
            case REMOVE_INDEX -> {
                requireIndex(working, action.getIndex());
                working.remove(action.getIndex());
                knownIndices.remove(action.getIndex());
            }
        }
    }

    private void requireIndex(Map<String, Map<String, AliasMetadata>> working, String index) {
        if (!working.containsKey(index)) {
            throw new IndexNotFoundException(index);
        }
    }

    private void validateWriteIndexUniqueness(Map<String, Map<String, AliasMetadata>> working) {
        Map<String, List<String>> explicitWriteIndicesByAlias = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, AliasMetadata>> indexEntry : working.entrySet()) {
            for (AliasMetadata alias : indexEntry.getValue().values()) {
                if (alias.isWriteIndexExplicit() && alias.isWriteIndex()) {
                    explicitWriteIndicesByAlias.computeIfAbsent(alias.getAlias(), k -> new java.util.ArrayList<>())
                        .add(alias.getIndex());
                }
            }
        }
        for (Map.Entry<String, List<String>> entry : explicitWriteIndicesByAlias.entrySet()) {
            if (entry.getValue().size() > 1) {
                throw new ElasticsearchException(
                    "alias [{}] has more than one write index [{}]; specify is_write_index for exactly one index",
                    entry.getKey(), String.join(", ", entry.getValue()));
            }
        }
    }

    private static Map<String, Map<String, AliasMetadata>> deepCopy(Map<String, Map<String, AliasMetadata>> source) {
        Map<String, Map<String, AliasMetadata>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, AliasMetadata>> entry : source.entrySet()) {
            copy.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
        }
        return copy;
    }

    public Map<String, AliasMetadata> getAliases(String index) {
        synchronized (lock) {
            Map<String, AliasMetadata> forIndex = aliasesByIndex.get(index);
            return forIndex == null ? Map.of() : Map.copyOf(forIndex);
        }
    }

    public Set<String> resolveIndices(String alias) {
        synchronized (lock) {
            Set<String> result = new LinkedHashSet<>();
            for (Map.Entry<String, Map<String, AliasMetadata>> entry : aliasesByIndex.entrySet()) {
                if (entry.getValue().containsKey(alias)) {
                    result.add(entry.getKey());
                }
            }
            return result;
        }
    }

    public String resolveWriteIndex(String alias) {
        synchronized (lock) {
            String explicitWriteIndex = null;
            List<String> candidates = new java.util.ArrayList<>();
            for (Map.Entry<String, Map<String, AliasMetadata>> entry : aliasesByIndex.entrySet()) {
                AliasMetadata metadata = entry.getValue().get(alias);
                if (metadata == null) {
                    continue;
                }
                candidates.add(entry.getKey());
                if (metadata.isWriteIndexExplicit() && metadata.isWriteIndex()) {
                    explicitWriteIndex = entry.getKey();
                }
            }
            if (explicitWriteIndex != null) {
                return explicitWriteIndex;
            }
            if (candidates.size() == 1) {
                return candidates.get(0);
            }
            return null;
        }
    }

    public boolean aliasExists(String alias) {
        return !resolveIndices(alias).isEmpty();
    }
}
