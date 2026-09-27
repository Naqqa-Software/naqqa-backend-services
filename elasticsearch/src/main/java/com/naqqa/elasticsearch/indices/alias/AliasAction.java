package com.naqqa.elasticsearch.indices.alias;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AliasAction {

    public enum Type {
        ADD, REMOVE, REMOVE_INDEX
    }

    private final Type type;
    private final String index;
    private final String alias;
    private final Map<String, Object> filter;
    private final String indexRouting;
    private final String searchRouting;
    private final Boolean writeIndex;
    private final boolean mustExist;

    private AliasAction(Type type, String index, String alias, Map<String, Object> filter, String indexRouting,
                         String searchRouting, Boolean writeIndex, boolean mustExist) {
        this.type = type;
        this.index = index;
        this.alias = alias;
        this.filter = filter == null ? Map.of() : Map.copyOf(filter);
        this.indexRouting = indexRouting;
        this.searchRouting = searchRouting;
        this.writeIndex = writeIndex;
        this.mustExist = mustExist;
    }

    public Type getType() {
        return type;
    }

    public String getIndex() {
        return index;
    }

    public String getAlias() {
        return alias;
    }

    public Map<String, Object> getFilter() {
        return filter;
    }

    public String getIndexRouting() {
        return indexRouting;
    }

    public String getSearchRouting() {
        return searchRouting;
    }

    public Boolean getWriteIndex() {
        return writeIndex;
    }

    public boolean isMustExist() {
        return mustExist;
    }

    public static AliasAction removeIndex(String index) {
        return new AliasAction(Type.REMOVE_INDEX, index, null, null, null, null, null, false);
    }

    public static Builder add() {
        return new Builder(Type.ADD);
    }

    public static Builder remove() {
        return new Builder(Type.REMOVE);
    }

    public static final class Builder {
        private final Type type;
        private String index;
        private String alias;
        private Map<String, Object> filter = new LinkedHashMap<>();
        private String indexRouting;
        private String searchRouting;
        private Boolean writeIndex;
        private boolean mustExist;

        private Builder(Type type) {
            this.type = type;
        }

        public Builder index(String index) {
            this.index = index;
            return this;
        }

        public Builder alias(String alias) {
            this.alias = alias;
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
            this.writeIndex = writeIndex;
            return this;
        }

        public Builder mustExist(boolean mustExist) {
            this.mustExist = mustExist;
            return this;
        }

        public AliasAction build() {
            if (index == null) {
                throw new IllegalArgumentException("alias action requires an index");
            }
            if (type != Type.REMOVE_INDEX && alias == null) {
                throw new IllegalArgumentException("alias action requires an alias name");
            }
            return new AliasAction(type, index, alias, filter, indexRouting, searchRouting, writeIndex, mustExist);
        }
    }
}
