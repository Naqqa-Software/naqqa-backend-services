package com.naqqa.elasticsearch.search.execution;

import java.io.IOException;
import java.util.Collection;

public interface CollectorManager<C extends Collector, T> {

    C newCollector() throws IOException;

    T reduce(Collection<C> collectors) throws IOException;
}
