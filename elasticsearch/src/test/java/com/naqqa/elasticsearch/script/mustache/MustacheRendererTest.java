package com.naqqa.elasticsearch.script.mustache;

import com.naqqa.elasticsearch.test.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;

public final class MustacheRendererTest {

    public MustacheRendererTest() {
    }

    @Test
    public void variableEscapingAndDottedNames() {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("name", "<b>Bob</b>");
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("city", "Springfield");
        ctx.put("address", address);
        assertEquals("&lt;b&gt;Bob&lt;/b&gt; from Springfield",
            new MustacheRenderer(Map.of(), false).renderTemplate("{{name}} from {{address.city}}", ctx));
        assertEquals("<b>Bob</b>", new MustacheRenderer(Map.of(), false).renderTemplate("{{{name}}}", ctx));
        assertEquals("<b>Bob</b>", new MustacheRenderer(Map.of(), false).renderTemplate("{{&name}}", ctx));
    }

    @Test
    public void sectionsListsAndInverted() {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("items", List.of(Map.of("n", "a"), Map.of("n", "b")));
        String out = new MustacheRenderer(Map.of(), false).renderTemplate("{{#items}}[{{n}}]{{/items}}", ctx);
        assertEquals("[a][b]", out);

        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("items", List.of());
        assertEquals("none", new MustacheRenderer(Map.of(), false).renderTemplate("{{^items}}none{{/items}}", empty));
    }

    @Test
    public void commentsAndSetDelimiters() {
        assertEquals("hello", new MustacheRenderer(Map.of(), false).renderTemplate("{{! a comment }}hello", Map.of()));
        assertEquals("value", new MustacheRenderer(Map.of(), false).renderTemplate("{{=<% %>=}}<%x%>", Map.of("x", "value")));
    }

    @Test
    public void esExtensionFunctions() {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("tags", List.of("a", "b", "c"));
        ctx.put("obj", Map.of("k", "v"));
        ctx.put("q", "a b");
        MustacheRenderer renderer = new MustacheRenderer(Map.of(), false);
        assertEquals("a,b,c", renderer.renderTemplate("{{#join}}tags{{/join}}", ctx));
        assertEquals("a|b|c", renderer.renderTemplate("{{#join delimiter='|'}}tags{{/join}}", ctx));
        assertEquals("{\"k\":\"v\"}", renderer.renderTemplate("{{#toJson}}obj{{/toJson}}", ctx));
        assertEquals("a+b", renderer.renderTemplate("{{#url}}q{{/url}}", ctx));
    }

    @Test
    public void jsonEscapingModeForSearchTemplates() {
        Map<String, Object> ctx = Map.of("value", "line1\nline2\"quoted\"");
        String out = new MustacheRenderer(Map.of(), true).renderTemplate("{{value}}", ctx);
        assertEquals("line1\\nline2\\\"quoted\\\"", out);
    }
}
