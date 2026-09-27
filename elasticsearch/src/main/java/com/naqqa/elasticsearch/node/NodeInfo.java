package com.naqqa.elasticsearch.node;

import java.util.List;
import java.util.Map;

public record NodeInfo(String id, String name, String clusterName, String host, String transportAddress,
                       String httpAddress, List<String> roles, String version, Map<String, String> settings) {

    public static final String VERSION = "8.13.0";
    public static final String BUILD_FLAVOR = "default";

    public NodeInfo withHttpAddress(String address) {
        return new NodeInfo(id, name, clusterName, host, transportAddress, address, roles, version, settings);
    }

    public NodeInfo withTransportAddress(String address) {
        return new NodeInfo(id, name, clusterName, host, address, httpAddress, roles, version, settings);
    }
}
