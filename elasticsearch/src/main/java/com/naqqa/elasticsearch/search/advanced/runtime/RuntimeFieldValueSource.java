package com.naqqa.elasticsearch.search.advanced.runtime;

import java.io.IOException;
import java.util.List;

public interface RuntimeFieldValueSource {

    List<Object> values(int doc) throws IOException;
}
