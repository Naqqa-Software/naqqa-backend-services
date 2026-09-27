package com.naqqa.elasticsearch.ingest.useragent;

public record UserAgentInfo(String name, String version, String osName, String osVersion, String osFull,
                             String deviceName, boolean mobile) {
}
