package com.naqqa.elasticsearch.index.recovery;

final class RecoveryActions {

    static final String START = "internal:index/recovery/start";
    static final String FILE_CHUNK = "internal:index/recovery/file_chunk";
    static final String TRANSLOG_OPS = "internal:index/recovery/translog_ops";
    static final String FINISH = "internal:index/recovery/finish";

    private RecoveryActions() {
    }
}
