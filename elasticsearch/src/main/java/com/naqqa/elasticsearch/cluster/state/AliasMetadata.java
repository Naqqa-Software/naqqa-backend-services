package com.naqqa.elasticsearch.cluster.state;

import com.naqqa.elasticsearch.cluster.io.GenericValueIO;
import com.naqqa.elasticsearch.cluster.io.StreamUtils;
import com.naqqa.elasticsearch.cluster.io.Writeable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Map;

public final class AliasMetadata implements Writeable {

    private final String alias;
    private final String indexRouting;
    private final String searchRouting;
    private final boolean writeIndex;
    private final boolean writeIndexExplicit;
    private final Map<String, Object> filter;

    public AliasMetadata(String alias, String indexRouting, String searchRouting, boolean writeIndex) {
        this(alias, indexRouting, searchRouting, writeIndex, writeIndex, Map.of());
    }

    public AliasMetadata(String alias, String indexRouting, String searchRouting, boolean writeIndex,
                          boolean writeIndexExplicit, Map<String, Object> filter) {
        this.alias = alias;
        this.indexRouting = indexRouting;
        this.searchRouting = searchRouting;
        this.writeIndex = writeIndex;
        this.writeIndexExplicit = writeIndexExplicit;
        this.filter = filter == null ? Map.of() : Map.copyOf(filter);
    }

    public String getAlias() {
        return alias;
    }

    public String getIndexRouting() {
        return indexRouting;
    }

    public String getSearchRouting() {
        return searchRouting;
    }

    public boolean isWriteIndex() {
        return writeIndex;
    }

    public boolean isWriteIndexExplicit() {
        return writeIndexExplicit;
    }

    public Map<String, Object> getFilter() {
        return filter;
    }

    public boolean hasFilter() {
        return !filter.isEmpty();
    }

    @Override
    public void writeTo(DataOutput out) throws IOException {
        StreamUtils.writeString(out, alias);
        StreamUtils.writeOptionalString(out, indexRouting);
        StreamUtils.writeOptionalString(out, searchRouting);
        out.writeBoolean(writeIndex);
        out.writeBoolean(writeIndexExplicit);
        GenericValueIO.writeMap(out, filter);
    }

    public static AliasMetadata readFrom(DataInput in) throws IOException {
        String alias = StreamUtils.readString(in);
        String indexRouting = StreamUtils.readOptionalString(in);
        String searchRouting = StreamUtils.readOptionalString(in);
        boolean writeIndex = in.readBoolean();
        boolean writeIndexExplicit = in.readBoolean();
        Map<String, Object> filter = GenericValueIO.readMap(in);
        return new AliasMetadata(alias, indexRouting, searchRouting, writeIndex, writeIndexExplicit, filter);
    }
}
