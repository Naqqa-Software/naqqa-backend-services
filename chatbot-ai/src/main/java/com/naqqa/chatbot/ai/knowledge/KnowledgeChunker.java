package com.naqqa.chatbot.ai.knowledge;

import com.naqqa.chatbot.ai.InputGuard;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class KnowledgeChunker {

    public static final int MAX_CHARS = 600;

    public record Section(String title, String text) {
    }

    public record CuratedDoc(String title, String path, String type, List<Section> sections) {
    }

    private static final Pattern SCRIPT = Pattern.compile("(?is)<\\s*(script|style|noscript|iframe)[^>]*>.*?<\\s*/\\s*\\1\\s*>");
    private static final Pattern BLOCK = Pattern.compile("(?i)<\\s*/?\\s*(p|div|br|li|ul|ol|h[1-6]|tr|table|section|article|blockquote)[^>]*>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?[0-9a-fA-F]+);");
    private static final Pattern SENTENCE = Pattern.compile("(?<=[.!?…])\\s+");

    private KnowledgeChunker() {
    }

    public static String stripHtml(String html) {
        if (html == null) {
            return "";
        }
        String s = SCRIPT.matcher(html).replaceAll(" ");
        s = BLOCK.matcher(s).replaceAll("\n");
        s = TAG.matcher(s).replaceAll(" ");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&laquo;", "«").replace("&raquo;", "»").replace("&ndash;", "–").replace("&mdash;", "—");
        Matcher m = NUMERIC_ENTITY.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = m.group(1);
            String rep = " ";
            try {
                int cp = v.startsWith("x") || v.startsWith("X") ? Integer.parseInt(v.substring(1), 16) : Integer.parseInt(v);
                rep = new String(Character.toChars(cp));
            } catch (RuntimeException ignored) {
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        s = sb.toString().replace('<', ' ').replace('>', ' ');
        s = s.replaceAll("[ \\t\\x0B\\f]+", " ").replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }

    public static String sanitize(String text, InputGuard guard) {
        if (text == null || text.isBlank()) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String paragraph : text.split("\\n")) {
            List<String> sentences = new ArrayList<>();
            for (String sentence : SENTENCE.split(paragraph)) {
                if (!sentence.isBlank() && (guard == null || guard.injectionReason(sentence) == null)) {
                    sentences.add(sentence.trim());
                }
            }
            kept.add(String.join(" ", sentences));
        }
        return String.join("\n", kept).replaceAll("\\n{3,}", "\n\n").trim();
    }

    public static List<String> chunk(String text, int maxChars) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        StringBuilder current = new StringBuilder();
        for (String paragraph : text.split("\\n\\s*\\n|\\n")) {
            String p = paragraph.trim();
            if (p.isEmpty()) {
                continue;
            }
            List<String> pieces = new ArrayList<>();
            if (p.length() > maxChars) {
                StringBuilder piece = new StringBuilder();
                for (String sentence : SENTENCE.split(p)) {
                    if (piece.length() > 0 && piece.length() + sentence.length() + 1 > maxChars) {
                        pieces.add(piece.toString().trim());
                        piece.setLength(0);
                    }
                    if (sentence.length() > maxChars) {
                        for (int i = 0; i < sentence.length(); i += maxChars) {
                            pieces.add(sentence.substring(i, Math.min(sentence.length(), i + maxChars)));
                        }
                        continue;
                    }
                    piece.append(sentence).append(' ');
                }
                if (piece.length() > 0) {
                    pieces.add(piece.toString().trim());
                }
            } else {
                pieces.add(p);
            }
            for (String piece : pieces) {
                if (current.length() > 0 && current.length() + piece.length() + 1 > maxChars) {
                    out.add(current.toString().trim());
                    current.setLength(0);
                }
                current.append(piece).append('\n');
            }
        }
        if (current.length() > 0) {
            out.add(current.toString().trim());
        }
        return out;
    }

    public static CuratedDoc parseCurated(String content, String fallbackTitle) {
        String title = fallbackTitle;
        String path = null;
        String type = "GUIDE";
        List<Section> sections = new ArrayList<>();
        String sectionTitle = null;
        StringBuilder body = new StringBuilder();
        for (String rawLine : content.replace("\r", "").split("\n")) {
            String line = rawLine.strip();
            if (line.startsWith("# ")) {
                title = line.substring(2).trim();
            } else if (line.startsWith("path:") && path == null) {
                path = line.substring(5).trim();
            } else if (line.startsWith("type:")) {
                type = line.substring(5).trim().toUpperCase();
            } else if (line.startsWith("## ")) {
                if (body.length() > 0) {
                    sections.add(new Section(sectionTitle, body.toString().trim()));
                    body.setLength(0);
                }
                sectionTitle = line.substring(3).trim();
            } else {
                body.append(line).append('\n');
            }
        }
        if (body.length() > 0 && !body.toString().isBlank()) {
            sections.add(new Section(sectionTitle, body.toString().trim()));
        }
        return new CuratedDoc(title, path, type, sections);
    }
}
