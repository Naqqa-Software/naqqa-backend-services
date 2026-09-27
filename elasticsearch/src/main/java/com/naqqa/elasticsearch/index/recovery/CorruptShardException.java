package com.naqqa.elasticsearch.index.recovery;

import java.io.IOException;

public final class CorruptShardException extends IOException {

    public CorruptShardException(String message) {
        super(message);
    }

    public CorruptShardException(String message, Throwable cause) {
        super(message, cause);
    }
}
