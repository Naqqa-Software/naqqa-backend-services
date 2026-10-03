package com.naqqa.chatbot.ai;

import com.naqqa.chatbot.i18n.ChatLanguages;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class InputGuard {

    public static final int MAX_LENGTH = 500;

    public record Result(String text, String lang, boolean flagged, String reason) {
    }

    private static final List<Pattern> CODE = List.of(
            Pattern.compile("```"),
            Pattern.compile("(?i)<\\s*/?\\s*(?:script|iframe|img|svg|style|object|embed|a|div|html|body)\\b"),
            Pattern.compile("(?i)<\\?php"),
            Pattern.compile("(?i)\\b(?:select\\s+.+\\s+from|drop\\s+table|insert\\s+into|union\\s+select|delete\\s+from)\\b"),
            Pattern.compile("\\$\\{|\\{\\{|\\}\\}"),
            Pattern.compile("(?i)\\b(?:eval|exec|system)\\s*\\("),
            Pattern.compile("(?i)\\brm\\s+-rf\\b"),
            Pattern.compile("(?i)\\bimport\\s+(?:os|sys|subprocess)\\b"),
            Pattern.compile("(?i)javascript\\s*:")
    );

    private static final Pattern URL = Pattern.compile(
            "(?i)(?:https?://|ftp://|www\\.)\\S+|\\b[a-z0-9][a-z0-9\\-]{0,62}\\.(?:com|net|org|ru|ro|md|io|ua|info|biz|xyz|ly|me|co|app|dev|site|online|top|link|eu|uk|de|fr|us|tk|gg|tv|ai)\\b(?:/\\S*)?");

    private final ChatLanguages languages;
    private final Set<String> allowedHosts;

    public InputGuard(ChatLanguages languages, Collection<String> allowedHosts) {
        this.languages = languages;
        this.allowedHosts = allowedHosts == null ? Set.of() : allowedHosts.stream()
                .filter(h -> h != null && !h.isBlank())
                .map(h -> h.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public ChatLanguages languages() {
        return languages;
    }

    public Result inspect(String rawText, String requestLang) {
        String text = TextNormalizer.clean(rawText);
        if (text.length() > MAX_LENGTH) {
            text = text.substring(0, MAX_LENGTH).trim();
        }
        String lang = languages.detect(text, requestLang);
        String reason = injectionReason(text);
        return new Result(text, lang, reason != null, reason);
    }

    public String injectionReason(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String folded = TextNormalizer.fold(text).replace('’', '\'').replaceAll("[\\-'`]", " ");
        for (Pattern pattern : languages.injectionPatterns()) {
            if (pattern.matcher(folded).find()) {
                return "injection";
            }
        }
        for (Pattern pattern : CODE) {
            if (pattern.matcher(text).find()) {
                return "code";
            }
        }
        if (containsExternalUrl(text)) {
            return "url";
        }
        return null;
    }

    public boolean containsExternalUrl(String text) {
        var m = URL.matcher(text);
        while (m.find()) {
            String host = m.group().toLowerCase().replaceFirst("^(?:https?|ftp)://", "");
            int slash = host.indexOf('/');
            if (slash >= 0) {
                host = host.substring(0, slash);
            }
            if (!allowedHosts.contains(host)) {
                return true;
            }
        }
        return false;
    }

    public String detectLanguage(String text, String fallback) {
        return languages.detect(text, fallback);
    }

    public String normalizeLang(String lang) {
        return languages.normalize(lang);
    }
}
