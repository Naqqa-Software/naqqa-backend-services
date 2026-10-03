package com.naqqa.chatbot.ai;

import com.naqqa.chatbot.entities.ChatCard;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OutputGuard {

    public static final int MAX_LENGTH = 900;
    public static final int RULE_MAX_LENGTH = 2400;
    private static final Pattern SCRIPT_BLOCK = Pattern.compile("(?is)<\\s*(script|style|iframe|object|embed)[^>]*>.*?<\\s*/\\s*\\1\\s*>");
    private static final Pattern TAG = Pattern.compile("(?s)<\\s*/?\\s*[a-zA-Z!?][^>]*>");
    private static final Pattern MD_IMAGE = Pattern.compile("!\\[([^\\]]*)]\\(([^)]*)\\)");
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]+)]\\(([^)\\s]*)[^)]*\\)");
    private static final Pattern ABSOLUTE_URL = Pattern.compile("(?i)\\b(?:https?|ftp|javascript|data|vbscript|file):\\S*|\\bwww\\.\\S+");
    private static final Pattern BARE_DOMAIN = Pattern.compile(
            "(?i)(?<![@\\w.])[a-z0-9][a-z0-9\\-]{0,62}(?:\\.[a-z0-9\\-]{1,63})*\\.(?:com|net|org|ru|ro|md|io|ua|info|biz|xyz|ly|me|co|app|dev|site|online|top|link|eu|uk|de|fr|us|tk|gg|tv|ai)\\b(?:/\\S*)?");
    private static final Pattern RELATIVE_PATH = Pattern.compile("(?<![\\w/.:])/[a-zA-Z][\\w\\-./?=&%]*");
    private static final Pattern MD_DECOR = Pattern.compile("(?m)^\\s{0,3}#{1,6}\\s+|`+|^\\s*>\\s?");
    private static final Pattern ENTITY = Pattern.compile("&(?:[a-zA-Z]+|#\\d+|#x[0-9a-fA-F]+);");
    private final List<String> internalPrefixes;
    private final Set<String> allowedDomains;
    private final List<String> preserved;

    public OutputGuard(Collection<String> internalPrefixes, Collection<String> allowedDomains, Collection<String> preserved) {
        this.internalPrefixes = internalPrefixes == null ? List.of() : List.copyOf(internalPrefixes);
        this.allowedDomains = allowedDomains == null ? Set.of() : allowedDomains.stream()
                .filter(d -> d != null && !d.isBlank()).map(d -> d.trim().toLowerCase()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<String> keep = new ArrayList<>();
        if (preserved != null) {
            for (String p : preserved) {
                if (p != null && !p.isBlank()) {
                    keep.add(p);
                }
            }
        }
        this.preserved = List.copyOf(keep);
    }

    private static String marker(int index) {
        return "\u0001" + (char) ('A' + index) + "\u0001";
    }

    public String sanitize(String text) {
        return sanitize(text, MAX_LENGTH);
    }

    public String sanitize(String text, int max) {
        if (text == null) {
            return "";
        }
        String out = text.replace("\r", "");
        out = SCRIPT_BLOCK.matcher(out).replaceAll(" ");
        out = TAG.matcher(out).replaceAll(" ");
        out = out.replace("<", "‹").replace(">", "›");
        out = ENTITY.matcher(out).replaceAll(" ");
        out = MD_IMAGE.matcher(out).replaceAll("");
        out = replaceLinks(out);
        out = ABSOLUTE_URL.matcher(out).replaceAll("");
        out = replaceDomains(out);
        out = replaceRelativePaths(out);
        out = MD_DECOR.matcher(out).replaceAll("");
        for (int i = 0; i < preserved.size(); i++) {
            out = out.replace(preserved.get(i), marker(i));
        }
        out = PiiMasker.mask(out);
        for (int i = 0; i < preserved.size(); i++) {
            out = out.replace(marker(i), preserved.get(i));
        }
        out = out.replaceAll("[ \\t]{2,}", " ").replaceAll("\\n{3,}", "\n\n").replaceAll(" +([,.;:!?])", "$1").trim();
        return cap(out, max <= 0 ? MAX_LENGTH : max);
    }

    public boolean isInternalPath(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//")) {
            return false;
        }
        String lower = path.toLowerCase();
        if (lower.contains("..") || lower.contains("\\") || lower.contains(":")) {
            return false;
        }
        for (String prefix : internalPrefixes) {
            if (prefix.endsWith("/")) {
                if (lower.startsWith(prefix) && lower.length() > prefix.length()) {
                    return true;
                }
            } else if (lower.equals(prefix) || lower.startsWith(prefix + "/") || lower.startsWith(prefix + "?")) {
                return true;
            }
        }
        return false;
    }

    public List<ChatCard> verifyCards(List<ChatCard> cards, Collection<String> allowedKeys, LocalDate today) {
        List<ChatCard> out = new ArrayList<>();
        if (cards == null) {
            return out;
        }
        for (ChatCard card : cards) {
            if (card == null || card.getId() == null || card.getType() == null) {
                continue;
            }
            if (allowedKeys != null && !allowedKeys.contains(card.getType() + ":" + card.getId())) {
                continue;
            }
            if (!isInternalPath(card.getPath())) {
                continue;
            }
            if (card.getValidTo() != null) {
                try {
                    if (LocalDate.parse(card.getValidTo()).isBefore(today)) {
                        continue;
                    }
                } catch (DateTimeParseException ignored) {
                }
            }
            out.add(card);
        }
        return out;
    }

    private String replaceLinks(String text) {
        Matcher m = MD_LINK.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String label = m.group(1);
            String target = m.group(2);
            String cleanLabel = label.replace("[", "").replace("]", "").trim();
            String replacement = isInternalPath(target) ? "[" + cleanLabel + "](" + target + ")" : cleanLabel;
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String replaceDomains(String text) {
        Matcher m = BARE_DOMAIN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = m.group();
            String host = value.toLowerCase();
            int slash = host.indexOf('/');
            if (slash >= 0) {
                host = host.substring(0, slash);
            }
            String replacement = allowedDomains.contains(host) && slash < 0 ? value : "";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String replaceRelativePaths(String text) {
        Matcher m = RELATIVE_PATH.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = m.group();
            String trimmed = value.replaceAll("[.,;!?)]+$", "");
            String tail = value.substring(trimmed.length());
            m.appendReplacement(sb, Matcher.quoteReplacement(isInternalPath(trimmed) ? value : tail));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static String cap(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        String cut = text.substring(0, max);
        int end = Math.max(Math.max(cut.lastIndexOf(". "), cut.lastIndexOf("! ")), cut.lastIndexOf("? "));
        if (end > max / 2) {
            return cut.substring(0, end + 1).trim();
        }
        int space = cut.lastIndexOf(' ');
        return (space > max / 2 ? cut.substring(0, space) : cut).trim() + "…";
    }
}
