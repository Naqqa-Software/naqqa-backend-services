package com.naqqa.elasticsearch.analysis.hyphenation;

public final class Hyphen {

    private final String preBreak;
    private final String noBreak;
    private final String postBreak;

    public Hyphen(String pre, String no, String post) {
        this.preBreak = pre;
        this.noBreak = no;
        this.postBreak = post;
    }

    public Hyphen(String pre) {
        this(pre, null, null);
    }

    public String preBreak() {
        return preBreak;
    }

    public String noBreak() {
        return noBreak;
    }

    public String postBreak() {
        return postBreak;
    }

    @Override
    public String toString() {
        if (noBreak == null && postBreak == null && preBreak != null && preBreak.equals("-")) {
            return "-";
        }
        return "{" + preBreak + "}{" + postBreak + "}{" + noBreak + "}";
    }
}
