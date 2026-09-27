package com.naqqa.elasticsearch.cluster.state;

import com.naqqa.elasticsearch.cluster.io.Writeable;

public interface Custom extends Writeable {

    String getWriteableName();
}
