package com.naqqa.elasticsearch.indices.alias;

import com.naqqa.elasticsearch.common.exception.ElasticsearchException;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

public final class AliasServiceTest {

    @Test
    public void addAndRemoveAlias() {
        AliasService service = new AliasService();
        service.registerIndex("logs-1");
        service.applyActions(List.of(AliasAction.add().index("logs-1").alias("logs").build()));
        Assert.assertTrue(service.getAliases("logs-1").containsKey("logs"));
        Assert.assertEquals(java.util.Set.of("logs-1"), service.resolveIndices("logs"));

        service.applyActions(List.of(AliasAction.remove().index("logs-1").alias("logs").build()));
        Assert.assertFalse(service.getAliases("logs-1").containsKey("logs"));
    }

    @Test
    public void swapAliasAtomically() {
        AliasService service = new AliasService();
        service.registerIndex("logs-1");
        service.registerIndex("logs-2");
        service.applyActions(List.of(AliasAction.add().index("logs-1").alias("logs").writeIndex(true).build()));

        service.applyActions(List.of(
            AliasAction.remove().index("logs-1").alias("logs").build(),
            AliasAction.add().index("logs-2").alias("logs").writeIndex(true).build()));

        Assert.assertEquals(java.util.Set.of("logs-2"), service.resolveIndices("logs"));
        Assert.assertEquals("logs-2", service.resolveWriteIndex("logs"));
    }

    @Test
    public void batchFailsAtomicallyWhenOneActionInvalid() {
        AliasService service = new AliasService();
        service.registerIndex("logs-1");
        service.applyActions(List.of(AliasAction.add().index("logs-1").alias("logs").build()));

        Assert.assertThrows(RuntimeException.class, () -> service.applyActions(List.of(
            AliasAction.remove().index("logs-1").alias("logs").build(),
            AliasAction.add().index("missing-index").alias("logs").build())));

        Assert.assertTrue(service.getAliases("logs-1").containsKey("logs"));
    }

    @Test
    public void rejectsMultipleExplicitWriteIndices() {
        AliasService service = new AliasService();
        service.registerIndex("logs-1");
        service.registerIndex("logs-2");

        Assert.assertThrows(ElasticsearchException.class, () -> service.applyActions(List.of(
            AliasAction.add().index("logs-1").alias("logs").writeIndex(true).build(),
            AliasAction.add().index("logs-2").alias("logs").writeIndex(true).build())));
    }

    @Test
    public void implicitWriteIndexWhenSingleIndex() {
        AliasService service = new AliasService();
        service.registerIndex("logs-1");
        service.applyActions(List.of(AliasAction.add().index("logs-1").alias("logs").build()));
        Assert.assertEquals("logs-1", service.resolveWriteIndex("logs"));
    }

    @Test
    public void ambiguousWriteIndexWithoutExplicitFlag() {
        AliasService service = new AliasService();
        service.registerIndex("logs-1");
        service.registerIndex("logs-2");
        service.applyActions(List.of(
            AliasAction.add().index("logs-1").alias("logs").build(),
            AliasAction.add().index("logs-2").alias("logs").build()));
        Assert.assertNull(service.resolveWriteIndex("logs"));
    }

    @Test
    public void aliasWithFilterAndRouting() {
        AliasService service = new AliasService();
        service.registerIndex("logs-1");
        service.applyActions(List.of(AliasAction.add().index("logs-1").alias("logs-eu")
            .filter(Map.of("term", Map.of("region", "eu")))
            .routing("eu")
            .build()));
        AliasMetadata metadata = service.getAliases("logs-1").get("logs-eu");
        Assert.assertTrue(metadata.hasFilter());
        Assert.assertEquals("eu", metadata.getIndexRouting());
    }

    @Test
    public void removeIndexClearsAliases() {
        AliasService service = new AliasService();
        service.registerIndex("logs-1");
        service.applyActions(List.of(AliasAction.add().index("logs-1").alias("logs").build()));
        service.applyActions(List.of(AliasAction.removeIndex("logs-1")));
        Assert.assertTrue(service.getAliases("logs-1").isEmpty());
        Assert.assertFalse(service.aliasExists("logs"));
    }
}
