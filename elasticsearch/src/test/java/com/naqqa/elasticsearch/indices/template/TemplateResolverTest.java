package com.naqqa.elasticsearch.indices.template;

import com.naqqa.elasticsearch.cluster.state.Settings;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

public final class TemplateResolverTest {

    @Test
    public void resolvesWithTwoComponentTemplatesAndOverride() {
        TemplateService service = new TemplateService();

        Template baseSettings = Template.builder()
            .settings(Settings.builder().put("index.number_of_shards", "3").put("index.number_of_replicas", "1").build())
            .mappings(Map.of("properties", Map.of("message", Map.of("type", "text"))))
            .build();
        service.putComponentTemplate(new ComponentTemplate("base", baseSettings, 1L, Map.of()), true);

        Template overrideSettings = Template.builder()
            .settings(Settings.builder().put("index.number_of_replicas", "2").build())
            .mappings(Map.of("properties", Map.of("host", Map.of("type", "keyword"))))
            .build();
        service.putComponentTemplate(new ComponentTemplate("override", overrideSettings, 1L, Map.of()), true);

        Template ownTemplate = Template.builder()
            .settings(Settings.builder().put("index.number_of_replicas", "5").build())
            .build();

        IndexTemplateV2 indexTemplate = new IndexTemplateV2("logs-template", List.of("logs-*"),
            List.of("base", "override"), 100, ownTemplate, false, null, Map.of());
        service.putIndexTemplate(indexTemplate, true);

        TemplateResolver resolver = new TemplateResolver(service);
        ResolvedTemplate resolved = resolver.simulateTemplateResolution("logs-2024", Settings.EMPTY);

        Assert.assertEquals("3", resolved.getSettings().get("index.number_of_shards"));
        Assert.assertEquals("5", resolved.getSettings().get("index.number_of_replicas"));
        Assert.assertTrue(resolved.getMappings().get("properties") instanceof Map);
    }

    @Test
    public void higherPriorityTemplateWins() {
        TemplateService service = new TemplateService();
        Template lowTemplate = Template.builder().settings(Settings.builder().put("index.codec", "low").build()).build();
        Template highTemplate = Template.builder().settings(Settings.builder().put("index.codec", "high").build()).build();
        service.putIndexTemplate(new IndexTemplateV2("low", List.of("data-*"), List.of(), 10, lowTemplate, false, null, Map.of()), true);
        service.putIndexTemplate(new IndexTemplateV2("high", List.of("data-*"), List.of(), 200, highTemplate, false, null, Map.of()), true);

        TemplateResolver resolver = new TemplateResolver(service);
        ResolvedTemplate resolved = resolver.simulateTemplateResolution("data-2024", Settings.EMPTY);
        Assert.assertEquals("high", resolved.getSettings().get("index.codec"));
    }

    @Test
    public void requestSettingsOverrideTemplateSettings() {
        TemplateService service = new TemplateService();
        Template template = Template.builder().settings(Settings.builder().put("index.number_of_shards", "3").build()).build();
        service.putIndexTemplate(new IndexTemplateV2("t", List.of("x-*"), List.of(), 1, template, false, null, Map.of()), true);

        TemplateResolver resolver = new TemplateResolver(service);
        ResolvedTemplate resolved = resolver.simulateTemplateResolution("x-1",
            Settings.builder().put("index.number_of_shards", "7").build());
        Assert.assertEquals("7", resolved.getSettings().get("index.number_of_shards"));
    }
}
