package com.naqqa.analytics.export;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class CsvWriter {

    private static final String BOM = "﻿";

    private CsvWriter() {
    }

    public static byte[] write(List<Table> tables) {
        StringBuilder sb = new StringBuilder(BOM);
        boolean first = true;
        for (Table t : tables) {
            if (!first) {
                sb.append("\r\n");
            }
            first = false;
            if (tables.size() > 1) {
                sb.append(cell("# " + t.name())).append("\r\n");
            }
            line(sb, t.headers());
            for (List<Object> row : t.rows()) {
                line(sb, row);
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void line(StringBuilder sb, List<?> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(cell(values.get(i)));
        }
        sb.append("\r\n");
    }

    static String cell(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        String s = String.valueOf(v);
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
