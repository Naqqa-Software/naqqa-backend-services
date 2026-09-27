package com.naqqa.elasticsearch.indices.ilm;

import java.util.Map;

public interface IndexLifecycleActionExecutor {

    String rolloverIndex(String index);

    String shrinkIndex(String index, int numberOfShards);

    void forceMergeIndex(String index, int maxNumSegments);

    void setIndexReadOnly(String index, boolean readOnly);

    void allocateIndex(String index, Map<String, String> routingSettings, Integer numberOfReplicas);

    void deleteIndex(String index);

    void setIndexPriority(String index, int priority);
}
