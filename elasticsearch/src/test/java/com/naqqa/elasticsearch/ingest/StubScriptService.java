package com.naqqa.elasticsearch.ingest;

import java.util.Map;

public final class StubScriptService implements IngestScriptService {

    @Override
    public Condition compileCondition(String source, String lang, Map<String, Object> params) {
        if (source.startsWith("ctx.")) {
            String rest = source.substring(4);
            int eq = rest.indexOf("==");
            if (eq >= 0) {
                String field = rest.substring(0, eq).trim();
                String literal = rest.substring(eq + 2).trim().replace("'", "");
                return doc -> literal.equals(doc.getFieldValue(field, Object.class, true));
            }
        }
        return doc -> true;
    }

    @Override
    public ScriptExecutor compileScript(String source, String lang, Map<String, Object> params) {
        return doc -> doc.setFieldValue("scripted", true);
    }
}
