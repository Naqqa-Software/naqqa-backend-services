package com.naqqa.elasticsearch.search.similarity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Explanation {

    private final boolean match;
    private final float value;
    private final String description;
    private final List<Explanation> details;

    private Explanation(boolean match, float value, String description, List<Explanation> details) {
        this.match = match;
        this.value = value;
        this.description = description;
        this.details = details;
    }

    public static Explanation match(float value, String description, Explanation... details) {
        return new Explanation(true, value, description, List.of(details));
    }

    public static Explanation match(float value, String description, List<Explanation> details) {
        return new Explanation(true, value, description, List.copyOf(details));
    }

    public static Explanation noMatch(String description, Explanation... details) {
        return new Explanation(false, 0f, description, List.of(details));
    }

    public static Explanation noMatch(String description, List<Explanation> details) {
        return new Explanation(false, 0f, description, List.copyOf(details));
    }

    public boolean isMatch() {
        return match;
    }

    public float value() {
        return value;
    }

    public String description() {
        return description;
    }

    public List<Explanation> details() {
        return details;
    }

    public void toString(StringBuilder sb, int depth) {
        sb.append("  ".repeat(depth));
        if (match) {
            sb.append(value).append(" = ").append(description).append('\n');
        } else {
            sb.append("0.0 = ").append(description).append('\n');
        }
        for (Explanation detail : details) {
            detail.toString(sb, depth + 1);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        toString(sb, 0);
        return sb.toString();
    }

    public List<Explanation> flatten() {
        List<Explanation> all = new ArrayList<>();
        collect(all);
        return Collections.unmodifiableList(all);
    }

    private void collect(List<Explanation> out) {
        out.add(this);
        for (Explanation detail : details) {
            detail.collect(out);
        }
    }
}
