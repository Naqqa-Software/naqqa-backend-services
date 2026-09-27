package com.naqqa.elasticsearch.rest.root;

import com.naqqa.elasticsearch.common.UUIDs;
import com.naqqa.elasticsearch.http.RestChannel;
import com.naqqa.elasticsearch.http.RestRequest;
import com.naqqa.elasticsearch.rest.support.RestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RootRestHandler {

    private final String clusterName;
    private final String nodeName;
    private final String clusterUuid = UUIDs.randomBase64UUID();

    public RootRestHandler(String clusterName, String nodeName) {
        this.clusterName = clusterName;
        this.nodeName = nodeName;
    }

    public void handle(RestRequest request, RestChannel channel) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", nodeName);
        body.put("cluster_name", clusterName);
        body.put("cluster_uuid", clusterUuid);
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("number", "1.0.0");
        version.put("build_flavor", "default");
        version.put("build_type", "naqqa");
        version.put("build_hash", "0000000");
        version.put("build_date", "2026-01-01T00:00:00.000Z");
        version.put("build_snapshot", false);
        version.put("lucene_version", "N/A");
        version.put("minimum_wire_compatibility_version", "1.0.0");
        version.put("minimum_index_compatibility_version", "1.0.0");
        body.put("version", version);
        body.put("tagline", "You Know, for Search");
        RestUtils.sendJson(channel, request, 200, body);
    }
}
