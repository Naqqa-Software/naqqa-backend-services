package com.naqqa.elasticsearch.analysis.lang.b;

final class SnowballAmong {

    final char[] s;
    final int substring_i;
    final int result;

    SnowballAmong(String s, int substring_i, int result) {
        this.s = s.toCharArray();
        this.substring_i = substring_i;
        this.result = result;
    }
}
