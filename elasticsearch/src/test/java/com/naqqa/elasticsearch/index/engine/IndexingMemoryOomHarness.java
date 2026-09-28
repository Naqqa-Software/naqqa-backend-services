package com.naqqa.elasticsearch.index.engine;

import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.translog.Durability;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public final class IndexingMemoryOomHarness {

    private IndexingMemoryOomHarness() {
    }

    public static void main(String[] args) throws Exception {
        Path shardPath = Paths.get(args[0]);
        int numDocs = Integer.parseInt(args[1]);
        long budgetBytes = Long.parseLong(args[2]);

        MapperService mapperService = EngineTestSupport.newMapperService();
        EngineConfig base = EngineTestSupport.newConfig(shardPath, mapperService);
        EngineConfig config = base
            .withMemoryController(new IndexingMemoryController(budgetBytes))
            .withTranslogConfig(base.translogConfig().withDurability(Durability.ASYNC));
        InternalEngine engine = InternalEngine.open(config);
        try {
            for (int i = 0; i < numDocs; i++) {
                engine.index(IndexOperation.of("d" + i, Map.of("title", "t" + i, "tag", "g" + (i % 100))));
            }
        } finally {
            engine.close();
        }
        System.out.println("DONE " + numDocs);
        System.out.flush();
    }
}
