package com.naqqa.elasticsearch.indices;

import com.naqqa.elasticsearch.cluster.state.ClusterBlock;
import com.naqqa.elasticsearch.cluster.state.ClusterBlockLevel;
import com.naqqa.elasticsearch.cluster.state.ClusterBlocks;
import com.naqqa.elasticsearch.common.exception.ClusterBlockException;

import java.util.EnumSet;

public final class IndexBlocks {

    public static final int READ_ONLY_ID = 101;
    public static final int READ_ONLY_ALLOW_DELETE_ID = 102;
    public static final int WRITE_ID = 103;
    public static final int METADATA_ID = 104;
    public static final int CLOSED_ID = 105;

    public static final ClusterBlock INDEX_READ_ONLY = new ClusterBlock(READ_ONLY_ID, "index read-only (index.blocks.read_only)",
        false, false, EnumSet.of(ClusterBlockLevel.WRITE, ClusterBlockLevel.METADATA_WRITE));

    public static final ClusterBlock INDEX_READ_ONLY_ALLOW_DELETE = new ClusterBlock(READ_ONLY_ALLOW_DELETE_ID,
        "index read-only / allow delete (index.blocks.read_only_allow_delete)", false, false,
        EnumSet.of(ClusterBlockLevel.WRITE, ClusterBlockLevel.METADATA_WRITE));

    public static final ClusterBlock INDEX_WRITE = new ClusterBlock(WRITE_ID, "index write (index.blocks.write)",
        false, false, EnumSet.of(ClusterBlockLevel.WRITE));

    public static final ClusterBlock INDEX_METADATA = new ClusterBlock(METADATA_ID, "index metadata (index.blocks.metadata)",
        false, false, EnumSet.of(ClusterBlockLevel.METADATA_READ, ClusterBlockLevel.METADATA_WRITE));

    public static final ClusterBlock INDEX_CLOSED = new ClusterBlock(CLOSED_ID, "index closed", false, false,
        EnumSet.of(ClusterBlockLevel.READ, ClusterBlockLevel.WRITE));

    private IndexBlocks() {
    }

    public static ClusterBlocks addBlock(ClusterBlocks blocks, String index, ClusterBlock block) {
        return blocks.toBuilder().addIndexBlock(index, block).build();
    }

    public static ClusterBlocks removeBlock(ClusterBlocks blocks, String index, ClusterBlock block) {
        java.util.Set<ClusterBlock> existing = blocks.indices().get(index);
        ClusterBlocks.Builder builder = blocks.toBuilder();
        builder.removeIndexBlocks(index);
        if (existing != null) {
            for (ClusterBlock b : existing) {
                if (b.getId() != block.getId()) {
                    builder.addIndexBlock(index, b);
                }
            }
        }
        return builder.build();
    }

    public static boolean isReadOnly(ClusterBlocks blocks, String index) {
        java.util.Set<ClusterBlock> existing = blocks.indices().get(index);
        if (existing == null) {
            return false;
        }
        for (ClusterBlock b : existing) {
            if (b.getId() == READ_ONLY_ID || b.getId() == READ_ONLY_ALLOW_DELETE_ID) {
                return true;
            }
        }
        return false;
    }

    public static void checkIndexBlocked(ClusterBlocks blocks, String index, ClusterBlockLevel level) {
        if (blocks.hasIndexBlock(index, level)) {
            throw new ClusterBlockException("index [{}] blocked by [{}]; blocked at level [{}]", index, index, level);
        }
    }

    public static void checkBlockedBeforeWrite(ClusterBlocks blocks, String index) {
        checkIndexBlocked(blocks, index, ClusterBlockLevel.WRITE);
    }

    public static void checkBlockedBeforeMetadataChange(ClusterBlocks blocks, String index) {
        checkIndexBlocked(blocks, index, ClusterBlockLevel.METADATA_WRITE);
    }
}
