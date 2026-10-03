package com.naqqa.analytics.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class XlsxWriter {

    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";

    private XlsxWriter() {
    }

    public static byte[] write(List<Table> tables) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            StringBuilder types = new StringBuilder(XML)
                    .append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
                    .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
                    .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
                    .append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>");
            for (int i = 1; i <= tables.size(); i++) {
                types.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                        .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
            }
            types.append("</Types>");
            put(zip, "[Content_Types].xml", types.toString());
            put(zip, "_rels/.rels", XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                    + "</Relationships>");
            StringBuilder wb = new StringBuilder(XML)
                    .append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" ")
                    .append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
            StringBuilder rels = new StringBuilder(XML).append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
            Set<String> used = new HashSet<>();
            for (int i = 1; i <= tables.size(); i++) {
                String name = sheetName(tables.get(i - 1).name(), i, used);
                wb.append("<sheet name=\"").append(esc(name)).append("\" sheetId=\"").append(i).append("\" r:id=\"rId").append(i).append("\"/>");
                rels.append("<Relationship Id=\"rId").append(i)
                        .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                        .append(i).append(".xml\"/>");
            }
            wb.append("</sheets></workbook>");
            rels.append("</Relationships>");
            put(zip, "xl/workbook.xml", wb.toString());
            put(zip, "xl/_rels/workbook.xml.rels", rels.toString());
            for (int i = 1; i <= tables.size(); i++) {
                put(zip, "xl/worksheets/sheet" + i + ".xml", sheet(tables.get(i - 1)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static String sheet(Table t) {
        StringBuilder sb = new StringBuilder(XML)
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        int r = 1;
        sb.append(row(r++, t.headers()));
        for (List<Object> row : t.rows()) {
            sb.append(row(r++, row));
        }
        return sb.append("</sheetData></worksheet>").toString();
    }

    private static String row(int r, List<?> values) {
        StringBuilder sb = new StringBuilder("<row r=\"").append(r).append("\">");
        for (int c = 0; c < values.size(); c++) {
            Object v = values.get(c);
            if (v == null) {
                continue;
            }
            String ref = column(c) + r;
            if (v instanceof Number n && Double.isFinite(n.doubleValue())) {
                sb.append("<c r=\"").append(ref).append("\"><v>").append(n).append("</v></c>");
            } else if (v instanceof Boolean b) {
                sb.append("<c r=\"").append(ref).append("\" t=\"b\"><v>").append(b ? 1 : 0).append("</v></c>");
            } else {
                sb.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(esc(String.valueOf(v)))
                        .append("</t></is></c>");
            }
        }
        return sb.append("</row>").toString();
    }

    static String column(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index + 1;
        while (n > 0) {
            int rem = (n - 1) % 26;
            sb.insert(0, (char) ('A' + rem));
            n = (n - 1) / 26;
        }
        return sb.toString();
    }

    private static String sheetName(String name, int index, Set<String> used) {
        String n = name == null || name.isBlank() ? "Sheet" + index : name.replaceAll("[\\[\\]:*?/\\\\]", "_");
        if (n.length() > 28) {
            n = n.substring(0, 28);
        }
        String base = n;
        int i = 2;
        while (!used.add(n.toLowerCase())) {
            n = base + "_" + i++;
        }
        return n;
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> {
                    if (ch >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
