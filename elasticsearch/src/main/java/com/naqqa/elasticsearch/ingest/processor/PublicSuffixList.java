package com.naqqa.elasticsearch.ingest.processor;

import java.util.Set;

public final class PublicSuffixList {

    private PublicSuffixList() {
    }

    public static final Set<String> MULTI_LEVEL_SUFFIXES = Set.of(
        "co.uk", "org.uk", "gov.uk", "ac.uk", "me.uk", "ltd.uk", "plc.uk", "net.uk", "sch.uk",
        "com.au", "net.au", "org.au", "edu.au", "gov.au", "id.au",
        "co.nz", "net.nz", "org.nz", "govt.nz",
        "co.jp", "or.jp", "ne.jp", "ac.jp", "go.jp",
        "co.kr", "or.kr", "ne.kr",
        "com.br", "net.br", "org.br", "gov.br",
        "co.za", "org.za", "gov.za", "net.za",
        "com.cn", "net.cn", "org.cn", "gov.cn",
        "com.mx", "gob.mx",
        "com.tr", "gov.tr",
        "co.in", "net.in", "org.in", "gov.in", "firm.in",
        "com.sg", "net.sg", "org.sg", "gov.sg",
        "com.hk", "net.hk", "org.hk", "gov.hk",
        "co.il", "org.il", "net.il", "gov.il",
        "com.ar", "net.ar", "org.ar", "gob.ar",
        "co.id", "or.id", "go.id", "ac.id",
        "github.io"
    );

    public static final Set<String> SINGLE_LEVEL_TLDS = Set.of(
        "com", "net", "org", "io", "dev", "co", "info", "biz", "app", "gov", "edu", "mil",
        "uk", "us", "de", "fr", "jp", "cn", "au", "ca", "nz", "in", "br", "ru", "eu", "me",
        "tv", "cc", "xyz", "online", "site", "tech", "ai", "id"
    );

    public static boolean isMultiLevelSuffix(String label1, String label0) {
        return MULTI_LEVEL_SUFFIXES.contains(label1 + "." + label0);
    }
}
