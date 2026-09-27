package com.naqqa.elasticsearch.indices.datastream;

import com.naqqa.elasticsearch.common.exception.ElasticsearchException;
import com.naqqa.elasticsearch.common.exception.ResourceAlreadyExistsException;
import com.naqqa.elasticsearch.indices.template.IndexTemplateV2;
import com.naqqa.elasticsearch.indices.template.ResolvedTemplate;
import com.naqqa.elasticsearch.indices.template.TemplateResolver;

import java.util.LinkedHashMap;
import java.util.Map;

public final class DataStreamService {

    private final TemplateResolver templateResolver;
    private final Map<String, DataStream> dataStreams = new LinkedHashMap<>();

    public DataStreamService(TemplateResolver templateResolver) {
        this.templateResolver = templateResolver;
    }

    public synchronized DataStream createDataStream(String name) {
        if (dataStreams.containsKey(name)) {
            throw new ResourceAlreadyExistsException("data stream [{}] already exists", name);
        }
        IndexTemplateV2 template = templateResolver.findMatchingIndexTemplate(name);
        if (template == null || !template.isDataStreamTemplate()) {
            throw new ElasticsearchException(
                "no matching index template with data_stream template found for data stream [{}]", name);
        }
        ResolvedTemplate resolved = templateResolver.simulateTemplateResolution(name, null);
        DataStream.validateTimestampMapping(template.getTimestampField(), resolved.getMappings());
        String firstIndex = DataStream.backingIndexName(name, 1);
        DataStream dataStream = new DataStream(name, java.util.List.of(firstIndex), 1, template.getTimestampField());
        dataStreams.put(name, dataStream);
        return dataStream;
    }

    public synchronized DataStream rollover(String name) {
        DataStream existing = requireDataStream(name);
        String newIndex = DataStream.backingIndexName(name, existing.getGeneration() + 1);
        DataStream updated = existing.rollover(newIndex);
        dataStreams.put(name, updated);
        return updated;
    }

    public synchronized void delete(String name) {
        if (dataStreams.remove(name) == null) {
            throw new DataStreamNotFoundException(name);
        }
    }

    public synchronized DataStream get(String name) {
        return dataStreams.get(name);
    }

    private DataStream requireDataStream(String name) {
        DataStream dataStream = dataStreams.get(name);
        if (dataStream == null) {
            throw new DataStreamNotFoundException(name);
        }
        return dataStream;
    }

    public synchronized void validateIndexTargeting(String dataStreamName, String indexName, boolean isWrite) {
        DataStream dataStream = requireDataStream(dataStreamName);
        if (!dataStream.getBackingIndices().contains(indexName)) {
            throw new ElasticsearchException(
                "index [{}] is not a backing index of data stream [{}]", indexName, dataStreamName);
        }
        if (isWrite && !indexName.equals(dataStream.getWriteIndex())) {
            throw new AppendOnlyViolationException(
                "index/update/delete requests targeting backing index [{}] of data stream [{}] are not allowed; "
                    + "only the write index [{}] can be targeted directly",
                indexName, dataStreamName, dataStream.getWriteIndex());
        }
    }
}
