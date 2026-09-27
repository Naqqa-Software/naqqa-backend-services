package com.naqqa.elasticsearch.cluster.state;

import com.naqqa.elasticsearch.cluster.io.StreamUtils;
import com.naqqa.elasticsearch.cluster.io.Writeable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public final class AliasMetadata implements Writeable {

    private final String alias;
    private final String indexRouting;
    private final String searchRouting;
    private final boolean writeIndex;

    public AliasMetadata(String alias, String indexRouting, String searchRouting, boolean writeIndex) {
        this.alias = alias;
        this.indexRouting = indexRouting;
        this.searchRouting = searchRouting;
        this.writeIndex = writeIndex;
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

    @Override
    public void writeTo(DataOutput out) throws IOException {
        StreamUtils.writeString(out, alias);
        StreamUtils.writeOptionalString(out, indexRouting);
        StreamUtils.writeOptionalString(out, searchRouting);
        out.writeBoolean(writeIndex);
    }

    public static AliasMetadata readFrom(DataInput in) throws IOException {
        return new AliasMetadata(StreamUtils.readString(in), StreamUtils.readOptionalString(in),
            StreamUtils.readOptionalString(in), in.readBoolean());
    }
}
