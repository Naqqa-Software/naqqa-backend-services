package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;
import com.naqqa.elasticsearch.common.json.JsonValue;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertNotNull;
import static com.naqqa.elasticsearch.test.Assert.assertNull;

public final class RuntimeFieldTest {

    @Test
    public void parsesRuntimeFieldDefinitionWithScript() {
        JsonObject node = (JsonObject) JsonValue.wrap(Map.of(
            "type", "keyword",
            "script", Map.of("source", "emit(doc['a'].value)", "lang", "painless")));
        RuntimeFieldMapper mapper = RuntimeFieldMapper.parse("computed", node);
        assertEquals("keyword", mapper.runtimeType());
        assertEquals("emit(doc['a'].value)", mapper.scriptSource());
        assertEquals("painless", mapper.scriptLang());
        RuntimeFieldScript compiled = mapper.compile((source, lang, params, targetType) -> sourceAsMap -> List.of("stub"));
        assertNotNull(compiled);
    }

    @Test
    public void mappingRuntimeSectionIsParsedIntoRootObjectMapper() {
        MapperService ms = MapperServiceTestSupport.newMapperService();
        ms.putMapping(Map.of(
            "runtime", Map.of("dayOfWeek", Map.of("type", "keyword", "script", Map.of("source", "emit('mon')"))),
            "properties", Map.of("a", Map.of("type", "keyword"))));
        RuntimeFieldMapper rf = ms.documentMapper().mapping().root().runtimeFields().get("dayOfWeek");
        assertNotNull(rf);
        assertEquals("keyword", rf.runtimeType());
    }

    @Test
    public void dynamicRuntimeModeProducesNoIndexedFieldsButRegistersDefinition() {
        MapperService ms = MapperServiceTestSupport.newMapperService();
        ms.putMapping(Map.of("dynamic", "runtime"));
        ParsedDocument doc = ms.parse("1", null, Map.of("label", "abc"));
        assertNull(ms.documentMapper().mapping().root().getMapper("label"));
        assertNotNull(ms.documentMapper().mapping().root().runtimeFields().get("label"));
    }
}
