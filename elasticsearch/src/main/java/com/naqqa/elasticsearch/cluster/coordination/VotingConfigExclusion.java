package com.naqqa.elasticsearch.cluster.coordination;

import com.naqqa.elasticsearch.cluster.io.StreamUtils;
import com.naqqa.elasticsearch.cluster.io.Writeable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public record VotingConfigExclusion(String nodeId, String nodeName) implements Writeable {

    @Override
    public void writeTo(DataOutput out) throws IOException {
        StreamUtils.writeString(out, nodeId);
        StreamUtils.writeString(out, nodeName);
    }

    public static VotingConfigExclusion readFrom(DataInput in) throws IOException {
        return new VotingConfigExclusion(StreamUtils.readString(in), StreamUtils.readString(in));
    }
}
