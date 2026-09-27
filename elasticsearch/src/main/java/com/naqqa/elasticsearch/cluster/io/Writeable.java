package com.naqqa.elasticsearch.cluster.io;

import java.io.DataOutput;
import java.io.IOException;

public interface Writeable {

    void writeTo(DataOutput out) throws IOException;
}
