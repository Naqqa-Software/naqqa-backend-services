package com.naqqa.elasticsearch.indices.template;

import com.naqqa.elasticsearch.cluster.state.Settings;
import com.naqqa.elasticsearch.indices.alias.AliasMetadata;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TemplateResolver {

    private final TemplateService templateService;

    public TemplateResolver(TemplateService templateService) {
        this.templateService = templateService;
    }

    public ResolvedTemplate simulateTemplateResolution(String indexName, Settings extraSettings) {
        return resolve(indexName, extraSettings, Map.of(), Map.of());
    }

    public ResolvedTemplate resolve(String indexName, Settings requestSettings, Map<String, Object> requestMappings,
                                     Map<String, AliasMetadata> requestAliases) {
        Settings settings = Settings.EMPTY;
        Map<String, Object> mappings = new LinkedHashMap<>();
        Map<String, AliasMetadata> aliases = new LinkedHashMap<>();

        IndexTemplateV2 matched = templateService.findMatchingIndexTemplate(indexName);
        if (matched != null) {
            for (String componentName : matched.getComposedOf()) {
                ComponentTemplate component = templateService.getComponentTemplate(componentName);
                if (component == null) {
                    continue;
                }
                Template fragment = component.getTemplate();
                settings = settings.merge(fragment.getSettings());
                mappings = MappingMerger.merge(mappings, fragment.getMappings());
                aliases.putAll(fragment.getAliases());
            }
            Template own = matched.getTemplate();
            settings = settings.merge(own.getSettings());
            mappings = MappingMerger.merge(mappings, own.getMappings());
            aliases.putAll(own.getAliases());
        } else {
            List<LegacyTemplate> legacy = templateService.findMatchingLegacyTemplates(indexName);
            for (LegacyTemplate template : legacy) {
                Template fragment = template.getTemplate();
                settings = settings.merge(fragment.getSettings());
                mappings = MappingMerger.merge(mappings, fragment.getMappings());
                aliases.putAll(fragment.getAliases());
            }
        }

        if (requestSettings != null) {
            settings = settings.merge(requestSettings);
        }
        if (requestMappings != null && !requestMappings.isEmpty()) {
            mappings = MappingMerger.merge(mappings, requestMappings);
        }
        if (requestAliases != null) {
            aliases.putAll(requestAliases);
        }

        return new ResolvedTemplate(settings, mappings, aliases);
    }

    public IndexTemplateV2 findMatchingIndexTemplate(String indexName) {
        return templateService.findMatchingIndexTemplate(indexName);
    }
}
