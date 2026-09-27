package com.naqqa.elasticsearch.index.mapper;

import java.util.Map;

public interface ScriptCompiler {

    RuntimeFieldScript compile(String source, String lang, Map<String, Object> params, String targetFieldType);
}
