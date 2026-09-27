package com.naqqa.elasticsearch.indices.template;

import com.naqqa.elasticsearch.cluster.state.Settings;
import com.naqqa.elasticsearch.indices.alias.AliasMetadata;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Template {

    public static final Template EMPTY = new Template(Settings.EMPTY, Map.of(), Map.of());

    private final Settings settings;
    private final Map<String, Object> mappings;
    private final Map<String, AliasMetadata> aliases;

    public Template(Settings settings, Map<String, Object> mappings, Map<String, AliasMetadata> aliases) {
        this.settings = settings == null ? Settings.EMPTY : settings;
        this.mappings = mappings == null ? Map.of() : Map.copyOf(mappings);
        this.aliases = aliases == null ? Map.of() : Map.copyOf(aliases);
    }

    public Settings getSettings() {
        return settings;
    }

    public Map<String, Object> getMappings() {
        return mappings;
    }

    public Map<String, AliasMetadata> getAliases() {
        return aliases;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Settings settings = Settings.EMPTY;
        private Map<String, Object> mappings = new LinkedHashMap<>();
        private Map<String, AliasMetadata> aliases = new LinkedHashMap<>();

        public Builder settings(Settings settings) {
            this.settings = settings;
            return this;
        }

        public Builder mappings(Map<String, Object> mappings) {
            this.mappings = mappings == null ? new LinkedHashMap<>() : new LinkedHashMap<>(mappings);
            return this;
        }

        public Builder putAlias(AliasMetadata alias) {
            this.aliases.put(alias.getAlias(), alias);
            return this;
        }

        public Builder aliases(Map<String, AliasMetadata> aliases) {
            this.aliases = aliases == null ? new LinkedHashMap<>() : new LinkedHashMap<>(aliases);
            return this;
        }

        public Template build() {
            return new Template(settings, mappings, aliases);
        }
    }
}
