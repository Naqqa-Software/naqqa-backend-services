package com.naqqa.elasticsearch.index.mapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class ContentPath {

    private final ArrayDeque<String> path = new ArrayDeque<>();

    public void add(String name) {
        path.addLast(name);
    }

    public void remove() {
        path.removeLast();
    }

    public String pathAsText(String name) {
        if (path.isEmpty()) {
            return name;
        }
        StringBuilder sb = new StringBuilder();
        for (String p : path) {
            sb.append(p).append('.');
        }
        sb.append(name);
        return sb.toString();
    }

    public int depth() {
        return path.size();
    }

    public List<String> segments() {
        return new ArrayList<>(path);
    }
}
