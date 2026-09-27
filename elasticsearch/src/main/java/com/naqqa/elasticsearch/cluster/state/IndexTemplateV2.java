package com.naqqa.elasticsearch.cluster.state;

import com.naqqa.elasticsearch.cluster.io.StreamUtils;
import com.naqqa.elasticsearch.cluster.io.Writeable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.List;

public final class IndexTemplateV2 implements Writeable {

    private final String name;
    private final List<String> indexPatterns;
    private final List<String> composedOf;
    private final long priority;
    private final Settings settings;
    private final boolean dataStreamTemplate;

    public IndexTemplateV2(String name, List<String> indexPatterns, List<String> composedOf, long priority,
                            Settings settings, boolean dataStreamTemplate) {
        this.name = name;
        this.indexPatterns = List.copyOf(indexPatterns);
        this.composedOf = List.copyOf(composedOf);
        this.priority = priority;
        this.settings = settings;
        this.dataStreamTemplate = dataStreamTemplate;
    }

    public String getName() {
        return name;
    }

    public List<String> getIndexPatterns() {
        return indexPatterns;
    }

    public List<String> getComposedOf() {
        return composedOf;
    }

    public long getPriority() {
        return priority;
    }

    public Settings getSettings() {
        return settings;
    }

    public boolean isDataStreamTemplate() {
        return dataStreamTemplate;
    }

    public boolean matches(String indexName) {
        for (String pattern : indexPatterns) {
            String regex = pattern.replace(".", "\\.").replace("*", ".*");
            if (indexName.matches(regex)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void writeTo(DataOutput out) throws IOException {
        StreamUtils.writeString(out, name);
        StreamUtils.writeStringCollection(out, indexPatterns);
        StreamUtils.writeStringCollection(out, composedOf);
        out.writeLong(priority);
        settings.writeTo(out);
        out.writeBoolean(dataStreamTemplate);
    }

    public static IndexTemplateV2 readFrom(DataInput in) throws IOException {
        String name = StreamUtils.readString(in);
        List<String> patterns = StreamUtils.readStringList(in);
        List<String> composedOf = StreamUtils.readStringList(in);
        long priority = in.readLong();
        Settings settings = Settings.readFrom(in);
        boolean dataStream = in.readBoolean();
        return new IndexTemplateV2(name, patterns, composedOf, priority, settings, dataStream);
    }
}
