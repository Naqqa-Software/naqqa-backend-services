package com.naqqa.elasticsearch.indices.datastream;

import com.naqqa.elasticsearch.common.exception.ElasticsearchException;
import com.naqqa.elasticsearch.indices.template.IndexTemplateV2;
import com.naqqa.elasticsearch.indices.template.Template;
import com.naqqa.elasticsearch.indices.template.TemplateResolver;
import com.naqqa.elasticsearch.indices.template.TemplateService;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

public final class DataStreamServiceTest {

    private DataStreamService buildService() {
        TemplateService templateService = new TemplateService();
        Template template = Template.builder()
            .mappings(Map.of("properties", Map.of("@timestamp", Map.of("type", "date"))))
            .build();
        templateService.putIndexTemplate(
            new IndexTemplateV2("logs-template", List.of("logs-*"), List.of(), 100, template, true, "@timestamp", Map.of()),
            true);
        TemplateResolver resolver = new TemplateResolver(templateService);
        return new DataStreamService(resolver);
    }

    @Test
    public void createGeneratesFirstBackingIndex() {
        DataStreamService service = buildService();
        DataStream stream = service.createDataStream("logs-app");
        Assert.assertEquals(1L, stream.getGeneration());
        Assert.assertEquals(".ds-logs-app-000001", stream.getWriteIndex());
        Assert.assertEquals(List.of(".ds-logs-app-000001"), stream.getBackingIndices());
    }

    @Test
    public void rolloverAppendsNewGeneration() {
        DataStreamService service = buildService();
        service.createDataStream("logs-app");
        DataStream rolled = service.rollover("logs-app");
        Assert.assertEquals(2L, rolled.getGeneration());
        Assert.assertEquals(".ds-logs-app-000002", rolled.getWriteIndex());
        Assert.assertEquals(List.of(".ds-logs-app-000001", ".ds-logs-app-000002"), rolled.getBackingIndices());

        DataStream rolledAgain = service.rollover("logs-app");
        Assert.assertEquals(3L, rolledAgain.getGeneration());
        Assert.assertEquals(".ds-logs-app-000003", rolledAgain.getWriteIndex());
    }

    @Test
    public void createWithoutMatchingTemplateFails() {
        DataStreamService service = buildService();
        Assert.assertThrows(ElasticsearchException.class, () -> service.createDataStream("metrics-app"));
    }

    @Test
    public void appendOnlyRejectsWritesToOldBackingIndex() {
        DataStreamService service = buildService();
        service.createDataStream("logs-app");
        service.rollover("logs-app");

        Assert.assertThrows(AppendOnlyViolationException.class,
            () -> service.validateIndexTargeting("logs-app", ".ds-logs-app-000001", true));

        service.validateIndexTargeting("logs-app", ".ds-logs-app-000002", true);
        service.validateIndexTargeting("logs-app", ".ds-logs-app-000001", false);
    }

    @Test
    public void validateIndexTargetingRejectsUnrelatedIndex() {
        DataStreamService service = buildService();
        service.createDataStream("logs-app");
        Assert.assertThrows(ElasticsearchException.class,
            () -> service.validateIndexTargeting("logs-app", "not-a-backing-index", false));
    }
}
