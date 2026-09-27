package com.naqqa.elasticsearch.script;

import java.util.Locale;

public enum ScriptType {
    INLINE("inline"),
    STORED("stored");

    private final String parseName;

    ScriptType(String parseName) {
        this.parseName = parseName;
    }

    public String parseName() {
        return parseName;
    }

    public static ScriptType fromName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (ScriptType t : values()) {
            if (t.parseName.equals(n)) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown script type [" + name + "]");
    }
}
