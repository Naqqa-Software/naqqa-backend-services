package com.naqqa.elasticsearch.indices.template;

import com.naqqa.elasticsearch.cluster.state.Settings;
import com.naqqa.elasticsearch.indices.alias.AliasMetadata;

import java.util.Map;

public final class ResolvedTemplate {

    private final Settings settings;
    private final Map<String, Object> mappings;
    private final Map<String, AliasMetadata> aliases;

    public ResolvedTemplate(Settings settings, Map<String, Object> mappings, Map<String, AliasMetadata> aliases) {
        this.settings = settings;
        this.mappings = Map.copyOf(mappings);
        this.aliases = Map.copyOf(aliases);
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
}
