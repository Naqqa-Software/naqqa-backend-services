package com.naqqa.elasticsearch.script.mustache;

import java.util.ArrayList;
import java.util.List;

public final class MustacheParser {

    private final String src;
    private int pos;
    private String open = "{{";
    private String close = "}}";

    private MustacheParser(String src) {
        this.src = src;
    }

    public static List<MustacheNode.Node> parse(String template) {
        MustacheParser parser = new MustacheParser(template);
        List<MustacheNode.Node> nodes = parser.parseNodes(null);
        if (parser.pos < parser.src.length()) {
            throw new IllegalArgumentException("unexpected trailing content in mustache template");
        }
        return nodes;
    }

    private List<MustacheNode.Node> parseNodes(String closingSectionName) {
        List<MustacheNode.Node> nodes = new ArrayList<>();
        while (pos < src.length()) {
            int tagStart = src.indexOf(open, pos);
            if (tagStart < 0) {
                nodes.add(new MustacheNode.Node.Text(src.substring(pos)));
                pos = src.length();
                break;
            }
            if (tagStart > pos) {
                nodes.add(new MustacheNode.Node.Text(src.substring(pos, tagStart)));
            }
            boolean triple = src.startsWith("{{{", tagStart) && open.equals("{{");
            String effectiveClose = triple ? "}}}" : close;
            int contentStart = tagStart + (triple ? 3 : open.length());
            int tagEnd = src.indexOf(effectiveClose, contentStart);
            if (tagEnd < 0) {
                throw new IllegalArgumentException("unterminated mustache tag at position " + tagStart);
            }
            String rawContent = src.substring(contentStart, tagEnd);
            pos = tagEnd + effectiveClose.length();
            if (triple) {
                nodes.add(new MustacheNode.Node.Variable(rawContent.trim(), false));
                continue;
            }
            if (rawContent.isEmpty()) {
                continue;
            }
            char sigil = rawContent.charAt(0);
            String body = rawContent.substring(1).trim();
            switch (sigil) {
                case '!' -> {
                }
                case '=' -> {
                    if (rawContent.endsWith("=")) {
                        String inner = rawContent.substring(1, rawContent.length() - 1).trim();
                        String[] parts = inner.split("\\s+");
                        if (parts.length == 2) {
                            open = parts[0];
                            close = parts[1];
                        }
                    }
                }
                case '&' -> nodes.add(new MustacheNode.Node.Variable(body, false));
                case '>' -> nodes.add(new MustacheNode.Node.Partial(body));
                case '#', '^' -> {
                    String rawBody = captureRawBody(body);
                    List<MustacheNode.Node> children = isFunctionSection(body) ? List.of() : parseNodes(sectionKey(body));
                    nodes.add(new MustacheNode.Node.Section(body, sigil == '^', children, rawBody));
                }
                case '/' -> {
                    if (closingSectionName == null || !sectionKey(closingSectionName).equals(body.trim())) {
                        throw new IllegalArgumentException("mismatched section close [" + body + "]");
                    }
                    return nodes;
                }
                default -> nodes.add(new MustacheNode.Node.Variable(rawContent.trim(), true));
            }
        }
        if (closingSectionName != null) {
            throw new IllegalArgumentException("unterminated section [" + closingSectionName + "]");
        }
        return nodes;
    }

    private boolean isFunctionSection(String body) {
        String name = sectionKey(body);
        return name.equals("toJson") || name.equals("join") || name.equals("url");
    }

    private String sectionKey(String body) {
        int space = body.indexOf(' ');
        return (space < 0 ? body : body.substring(0, space)).trim();
    }

    private String captureRawBody(String body) {
        if (!isFunctionSection(body)) {
            return null;
        }
        String name = sectionKey(body);
        int savedPos = pos;
        int closeTagStart = src.indexOf(open + "/" + name + close, pos);
        if (closeTagStart < 0) {
            throw new IllegalArgumentException("unterminated section [" + name + "]");
        }
        String raw = src.substring(pos, closeTagStart);
        pos = closeTagStart + (open + "/" + name + close).length();
        return raw.trim();
    }
}
