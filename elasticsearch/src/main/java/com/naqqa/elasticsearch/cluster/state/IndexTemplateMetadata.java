package com.naqqa.elasticsearch.cluster.state;

import com.naqqa.elasticsearch.cluster.io.StreamUtils;
import com.naqqa.elasticsearch.cluster.io.Writeable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.List;

public final class IndexTemplateMetadata implements Writeable {

    private final String name;
    private final List<String> patterns;
    private final int order;
    private final Settings settings;

    public IndexTemplateMetadata(String name, List<String> patterns, int order, Settings settings) {
        this.name = name;
        this.patterns = List.copyOf(patterns);
        this.order = order;
        this.settings = settings;
    }

    public String getName() {
        return name;
    }

    public List<String> getPatterns() {
        return patterns;
    }

    public int getOrder() {
        return order;
    }

    public Settings getSettings() {
        return settings;
    }

    public boolean matches(String indexName) {
        for (String pattern : patterns) {
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
        StreamUtils.writeStringCollection(out, patterns);
        StreamUtils.writeVInt(out, order);
        settings.writeTo(out);
    }

    public static IndexTemplateMetadata readFrom(DataInput in) throws IOException {
        String name = StreamUtils.readString(in);
        List<String> patterns = StreamUtils.readStringList(in);
        int order = StreamUtils.readVInt(in);
        Settings settings = Settings.readFrom(in);
        return new IndexTemplateMetadata(name, patterns, order, settings);
    }
}
