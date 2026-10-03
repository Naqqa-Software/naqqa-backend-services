package com.naqqa.analytics.export;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public record Table(String name, List<String> headers, List<List<Object>> rows) {

    public static Table of(String name, String... headers) {
        return new Table(name, Arrays.asList(headers), new ArrayList<>());
    }

    public Table row(Object... values) {
        rows.add(Arrays.asList(values));
        return this;
    }
}
