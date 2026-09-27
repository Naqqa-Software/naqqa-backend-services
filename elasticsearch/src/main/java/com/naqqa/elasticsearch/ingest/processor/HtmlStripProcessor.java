package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.Processor;

import java.util.Map;
import java.util.regex.Pattern;

public final class HtmlStripProcessor {

    public static final String TYPE = "html_strip";

    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(\\d+);");

    private static final Map<String, String> ENTITIES = Map.of(
        "&amp;", "&", "&lt;", "<", "&gt;", ">", "&quot;", "\"", "&apos;", "'", "&nbsp;", " "
    );

    private HtmlStripProcessor() {
    }

    public static String strip(String value) {
        String noTags = TAG.matcher(value).replaceAll("");
        String result = noTags;
        for (Map.Entry<String, String> e : ENTITIES.entrySet()) {
            result = result.replace(e.getKey(), e.getValue());
        }
        java.util.regex.Matcher m = NUMERIC_ENTITY.matcher(result);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(result, last, m.start());
            sb.appendCodePoint(Integer.parseInt(m.group(1)));
            last = m.end();
        }
        sb.append(result, last, result.length());
        return sb.toString();
    }

    public static Processor.Factory factory() {
        return StringTransformProcessor.factory(TYPE, HtmlStripProcessor::strip);
    }
}
