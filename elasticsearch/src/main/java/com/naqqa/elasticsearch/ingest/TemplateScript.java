package com.naqqa.elasticsearch.ingest;

public final class TemplateScript {

    private TemplateScript() {
    }

    public static String render(String template, IngestDocument doc) {
        if (template == null) {
            return null;
        }
        if (template.indexOf("{{") < 0) {
            return template;
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int len = template.length();
        while (i < len) {
            int start = template.indexOf("{{", i);
            if (start < 0) {
                sb.append(template, i, len);
                break;
            }
            sb.append(template, i, start);
            int end = template.indexOf("}}", start + 2);
            if (end < 0) {
                sb.append(template.substring(start));
                break;
            }
            String expr = template.substring(start + 2, end).trim();
            if (expr.startsWith("{") && end + 1 < len && template.charAt(end - 1) == '}') {
                expr = expr.substring(0, expr.length());
            }
            Object value = resolveExpr(expr, doc);
            sb.append(value == null ? "" : String.valueOf(value));
            i = end + 2;
        }
        return sb.toString();
    }

    public static Object renderValue(String template, IngestDocument doc) {
        if (template == null) {
            return null;
        }
        String trimmed = template.trim();
        if (trimmed.startsWith("{{") && trimmed.endsWith("}}")) {
            String inner = trimmed.substring(2, trimmed.length() - 2);
            if (inner.indexOf("{{") < 0 && inner.indexOf("}}") < 0) {
                return resolveExpr(inner.trim(), doc);
            }
        }
        return render(template, doc);
    }

    private static Object resolveExpr(String expr, IngestDocument doc) {
        String path = expr;
        if (path.startsWith("{") && path.endsWith("}")) {
            path = path.substring(1, path.length() - 1).trim();
        }
        return doc.getFieldValue(path, Object.class, true);
    }
}
