package com.naqqa.elasticsearch.indices;

import com.naqqa.elasticsearch.cluster.state.ClusterBlockLevel;
import com.naqqa.elasticsearch.cluster.state.ClusterBlocks;
import com.naqqa.elasticsearch.cluster.state.IndexMetadata;
import com.naqqa.elasticsearch.common.exception.ClusterBlockException;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

public final class IndexBlocksTest {

    @Test
    public void readOnlyBlocksWrite() {
        ClusterBlocks blocks = IndexBlocks.addBlock(ClusterBlocks.EMPTY, "logs-1", IndexBlocks.INDEX_READ_ONLY);
        Assert.assertTrue(IndexBlocks.isReadOnly(blocks, "logs-1"));
        Assert.assertThrows(ClusterBlockException.class,
            () -> IndexBlocks.checkBlockedBeforeWrite(blocks, "logs-1"));
    }

    @Test
    public void readAllowedWhenReadOnly() {
        ClusterBlocks blocks = IndexBlocks.addBlock(ClusterBlocks.EMPTY, "logs-1", IndexBlocks.INDEX_READ_ONLY);
        IndexBlocks.checkIndexBlocked(blocks, "logs-1", ClusterBlockLevel.READ);
    }

    @Test
    public void removingBlockAllowsWriteAgain() {
        ClusterBlocks blocks = IndexBlocks.addBlock(ClusterBlocks.EMPTY, "logs-1", IndexBlocks.INDEX_READ_ONLY);
        blocks = IndexBlocks.removeBlock(blocks, "logs-1", IndexBlocks.INDEX_READ_ONLY);
        Assert.assertFalse(IndexBlocks.isReadOnly(blocks, "logs-1"));
        IndexBlocks.checkBlockedBeforeWrite(blocks, "logs-1");
    }

    @Test
    public void writeBlockDoesNotBlockMetadata() {
        ClusterBlocks blocks = IndexBlocks.addBlock(ClusterBlocks.EMPTY, "logs-1", IndexBlocks.INDEX_WRITE);
        Assert.assertThrows(ClusterBlockException.class,
            () -> IndexBlocks.checkBlockedBeforeWrite(blocks, "logs-1"));
        IndexBlocks.checkBlockedBeforeMetadataChange(blocks, "logs-1");
    }

    @Test
    public void closedIndexRejectsActions() {
        IndexMetadata metadata = IndexMetadata.builder("logs-1").build();
        Assert.assertFalse(IndexStateService.isClosed(metadata));
        IndexMetadata closed = IndexStateService.close(metadata);
        Assert.assertTrue(IndexStateService.isClosed(closed));
        Assert.assertThrows(ClosedIndexException.class, () -> IndexStateService.checkNotClosed(closed));

        IndexMetadata reopened = IndexStateService.open(closed);
        Assert.assertFalse(IndexStateService.isClosed(reopened));
        IndexStateService.checkNotClosed(reopened);
    }
}
