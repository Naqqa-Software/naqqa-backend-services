package com.naqqa.elasticsearch.cluster.state;

import com.naqqa.elasticsearch.cluster.io.StreamUtils;
import com.naqqa.elasticsearch.cluster.io.Writeable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public final class ComponentTemplate implements Writeable {

    private final String name;
    private final Settings settings;
    private final String mappingsJson;

    public ComponentTemplate(String name, Settings settings, String mappingsJson) {
        this.name = name;
        this.settings = settings;
        this.mappingsJson = mappingsJson == null ? "" : mappingsJson;
    }

    public String getName() {
        return name;
    }

    public Settings getSettings() {
        return settings;
    }

    public String getMappingsJson() {
        return mappingsJson;
    }

    @Override
    public void writeTo(DataOutput out) throws IOException {
        StreamUtils.writeString(out, name);
        settings.writeTo(out);
        StreamUtils.writeString(out, mappingsJson);
    }

    public static ComponentTemplate readFrom(DataInput in) throws IOException {
        return new ComponentTemplate(StreamUtils.readString(in), Settings.readFrom(in), StreamUtils.readString(in));
    }
}
