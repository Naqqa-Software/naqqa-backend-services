package com.naqqa.elasticsearch.indices;

import com.naqqa.elasticsearch.cluster.state.Settings;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

public final class IndexSortConfigTest {

    @Test
    public void parsesMultiFieldSortConfig() {
        Settings settings = Settings.builder()
            .put("index.sort.field", "timestamp,host")
            .put("index.sort.order", "desc,asc")
            .put("index.sort.mode", "max,")
            .put("index.sort.missing", "_last,_first")
            .build();
        IndexSortConfig config = IndexSortConfig.fromSettings(settings);
        Assert.assertEquals(2, config.getFields().size());
        Assert.assertEquals("timestamp", config.getFields().get(0).field());
        Assert.assertEquals(IndexSortConfig.Order.DESC, config.getFields().get(0).order());
        Assert.assertEquals(IndexSortConfig.Mode.MAX, config.getFields().get(0).mode());
        Assert.assertEquals("_last", config.getFields().get(0).missing());
        Assert.assertEquals("host", config.getFields().get(1).field());
        Assert.assertEquals(IndexSortConfig.Order.ASC, config.getFields().get(1).order());
        Assert.assertNull(config.getFields().get(1).mode());
    }

    @Test
    public void emptyWhenNoSortFieldConfigured() {
        IndexSortConfig config = IndexSortConfig.fromSettings(Settings.EMPTY);
        Assert.assertTrue(config.isEmpty());
    }
}
