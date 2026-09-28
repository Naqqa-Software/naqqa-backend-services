package com.naqqa.elasticsearch.search.execution;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SearchExecutors {

    private static final ExecutorService SHARED = Executors.newVirtualThreadPerTaskExecutor();

    private SearchExecutors() {
    }

    public static Executor shared() {
        return SHARED;
    }
}
