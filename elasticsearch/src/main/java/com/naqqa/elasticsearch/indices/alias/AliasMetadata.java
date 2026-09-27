package com.naqqa.elasticsearch.indices.alias;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AliasMetadata {

    private final String alias;
    private final String index;
    private final Map<String, Object> filter;
    private final String indexRouting;
    private final String searchRouting;
    private final boolean writeIndex;
    private final boolean writeIndexExplicit;

    public AliasMetadata(String alias, String index, Map<String, Object> filter, String indexRouting,
                          String searchRouting, boolean writeIndex, boolean writeIndexExplicit) {
        this.alias = alias;
        this.index = index;
        this.filter = filter == null ? Map.of() : Map.copyOf(filter);
        this.indexRouting = indexRouting;
        this.searchRouting = searchRouting;
        this.writeIndex = writeIndex;
        this.writeIndexExplicit = writeIndexExplicit;
    }

    public String getAlias() {
        return alias;
    }

    public String getIndex() {
        return index;
    }

    public Map<String, Object> getFilter() {
        return filter;
    }

    public boolean hasFilter() {
        return !filter.isEmpty();
    }

    public String getIndexRouting() {
        return indexRouting;
    }

    public String getSearchRouting() {
        return searchRouting;
    }

    public boolean isWriteIndex() {
        return writeIndex;
    }

    public boolean isWriteIndexExplicit() {
        return writeIndexExplicit;
    }

    public AliasMetadata withWriteIndex(boolean newWriteIndex, boolean explicit) {
        return new AliasMetadata(alias, index, filter, indexRouting, searchRouting, newWriteIndex, explicit);
    }

    public AliasMetadata withIndex(String newIndex) {
        return new AliasMetadata(newIndex, index, filter, indexRouting, searchRouting, writeIndex, writeIndexExplicit);
    }

    public static AliasMetadata copyForIndex(AliasMetadata source, String index) {
        return new AliasMetadata(source.alias, index, source.filter, source.indexRouting, source.searchRouting,
            source.writeIndex, source.writeIndexExplicit);
    }

    public static Builder builder(String alias) {
        return new Builder(alias);
    }

    public static final class Builder {
        private final String alias;
        private String index;
        private Map<String, Object> filter = new LinkedHashMap<>();
        private String indexRouting;
        private String searchRouting;
        private boolean writeIndex;
        private boolean writeIndexExplicit;

        private Builder(String alias) {
            this.alias = alias;
        }

        public Builder index(String index) {
            this.index = index;
            return this;
        }

        public Builder filter(Map<String, Object> filter) {
            this.filter = filter == null ? new LinkedHashMap<>() : new LinkedHashMap<>(filter);
            return this;
        }

        public Builder indexRouting(String indexRouting) {
            this.indexRouting = indexRouting;
            return this;
        }

        public Builder searchRouting(String searchRouting) {
            this.searchRouting = searchRouting;
            return this;
        }

        public Builder routing(String routing) {
            this.indexRouting = routing;
            this.searchRouting = routing;
            return this;
        }

        public Builder writeIndex(Boolean writeIndex) {
            if (writeIndex == null) {
                this.writeIndex = false;
                this.writeIndexExplicit = false;
            } else {
                this.writeIndex = writeIndex;
                this.writeIndexExplicit = true;
            }
            return this;
        }

        public AliasMetadata build() {
            return new AliasMetadata(alias, index, filter, indexRouting, searchRouting, writeIndex, writeIndexExplicit);
        }
    }
}
