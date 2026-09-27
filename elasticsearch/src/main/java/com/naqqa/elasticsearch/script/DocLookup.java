package com.naqqa.elasticsearch.script;

import java.util.Map;

public interface DocLookup {

    ScriptDocValues<?> get(String field);

    boolean containsKey(String field);

    default int docId() {
        return 0;
    }

    static DocLookup of(Map<String, ? extends ScriptDocValues<?>> fields) {
        return of(fields, 0);
    }

    static DocLookup of(Map<String, ? extends ScriptDocValues<?>> fields, int docId) {
        return new DocLookup() {
            @Override
            public ScriptDocValues<?> get(String field) {
                ScriptDocValues<?> v = fields.get(field);
                if (v == null) {
                    throw new IllegalArgumentException("No field found for [" + field + "] in mapping");
                }
                return v;
            }

            @Override
            public boolean containsKey(String field) {
                return fields.containsKey(field);
            }

            @Override
            public int docId() {
                return docId;
            }

            @Override
            public String toString() {
                return "doc" + fields.keySet();
            }
        };
    }
}
