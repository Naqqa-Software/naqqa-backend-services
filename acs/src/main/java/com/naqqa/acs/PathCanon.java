package com.naqqa.acs;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PathCanon {
    public final String path;
    public final boolean suspicious;
    public final boolean ambiguous;

    private PathCanon(String path, boolean suspicious, boolean ambiguous) {
        this.path = path;
        this.suspicious = suspicious;
        this.ambiguous = ambiguous;
    }

    private static final Pattern PCT = Pattern.compile("%([0-9a-fA-F]{2})");
    private static final Pattern CTRL_OR_BACKSLASH = Pattern.compile("[\\x00-\\x1f\\\\]");

    private static boolean unreserved(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '~' || c == '-';
    }

    public static PathCanon of(String raw) {
        boolean suspicious = false;
        boolean ambiguous = false;
        String p = raw == null ? "/" : raw;
        if (p.length() > 2048) {
            p = p.substring(0, 2048);
            suspicious = true;
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
            suspicious = true;
        }
        Matcher m = PCT.matcher(p);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            char c = (char) Integer.parseInt(m.group(1), 16);
            String rep;
            if (unreserved(c)) {
                suspicious = true;
                rep = String.valueOf(c);
            } else {
                if (c == '/' || c == '\\' || c == '%' || c == ';' || c < 32) ambiguous = true;
                rep = m.group().toUpperCase();
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        p = sb.toString();
        if (CTRL_OR_BACKSLASH.matcher(p).find()) ambiguous = true;
        List<String> segs = new ArrayList<>();
        for (String seg : p.split("/", -1)) {
            int semi = seg.indexOf(';');
            if (semi >= 0) {
                seg = seg.substring(0, semi);
                suspicious = true;
            }
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (!segs.isEmpty()) segs.remove(segs.size() - 1);
                suspicious = true;
                continue;
            }
            segs.add(seg);
        }
        String out = "/" + String.join("/", segs);
        if (!out.equals(p) && !(out + "/").equals(p)) suspicious = true;
        return new PathCanon(out, suspicious || ambiguous, ambiguous);
    }

    public static boolean hasDotSegment(String raw) {
        if (raw == null) return false;
        Matcher m = PCT.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            char c = (char) Integer.parseInt(m.group(1), 16);
            m.appendReplacement(sb, Matcher.quoteReplacement(unreserved(c) ? String.valueOf(c) : m.group()));
        }
        m.appendTail(sb);
        for (String seg : sb.toString().split("/", -1)) {
            int semi = seg.indexOf(';');
            if (semi >= 0) seg = seg.substring(0, semi);
            if (seg.equals(".") || seg.equals("..")) return true;
        }
        return false;
    }
}
