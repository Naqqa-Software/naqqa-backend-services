package com.naqqa.analytics.config;

import com.mongodb.MongoCommandException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;

import java.util.Set;

@Slf4j
public final class MongoIndexSupport {

    private static final Set<Integer> EQUIVALENT_INDEX_CODES = Set.of(85, 86);

    private MongoIndexSupport() {
    }

    public static void ensure(IndexOperations ops, Index index) {
        try {
            ops.ensureIndex(index);
        } catch (RuntimeException e) {
            MongoCommandException command = findMongoCommandException(e);
            if (command != null && EQUIVALENT_INDEX_CODES.contains(command.getErrorCode())) {
                log.debug("[analytics] index {} already exists in an equivalent form: {}", index.getIndexKeys().toJson(), command.getErrorMessage());
                return;
            }
            log.warn("[analytics] index {} could not be ensured: {}", index.getIndexKeys().toJson(), e.getMessage());
        }
    }

    private static MongoCommandException findMongoCommandException(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof MongoCommandException command) {
                return command;
            }
            t = t.getCause();
        }
        return null;
    }
}
