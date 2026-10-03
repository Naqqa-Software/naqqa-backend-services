package com.naqqa.chatbot.ai.safety;

import com.fasterxml.jackson.databind.JsonNode;
import com.naqqa.chatbot.ai.TextNormalizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AbuseGuard {

    public enum Category { NONE, PROFANITY, SEXUAL, HATE, SEXUAL_MINORS }

    private static final Map<Character, Character> LEET = Map.of(
            '0', 'o', '1', 'i', '3', 'e', '4', 'a', '@', 'a', '$', 's', '5', 's', '7', 't', '!', 'i', '|', 'i');
    private static final Map<Character, Character> CYR_TO_LAT = Map.ofEntries(
            Map.entry('а', 'a'), Map.entry('в', 'b'), Map.entry('е', 'e'), Map.entry('к', 'k'), Map.entry('м', 'm'),
            Map.entry('н', 'h'), Map.entry('о', 'o'), Map.entry('р', 'p'), Map.entry('с', 'c'), Map.entry('т', 't'),
            Map.entry('у', 'y'), Map.entry('х', 'x'), Map.entry('і', 'i'), Map.entry('ѕ', 's'));
    private static final Map<Character, Character> LAT_TO_CYR = Map.ofEntries(
            Map.entry('a', 'а'), Map.entry('b', 'в'), Map.entry('e', 'е'), Map.entry('k', 'к'), Map.entry('m', 'м'),
            Map.entry('h', 'н'), Map.entry('o', 'о'), Map.entry('p', 'р'), Map.entry('c', 'с'), Map.entry('t', 'т'),
            Map.entry('y', 'у'), Map.entry('x', 'х'), Map.entry('u', 'и'), Map.entry('i', 'и'));
    private static final Pattern SPLIT = Pattern.compile("[\\s,;:?()\\[\\]{}\"«»„“”/\\\\]+");
    private static final Pattern SEPARATORS = Pattern.compile("[._\\-'`’+~^=#%&]");
    private static final Pattern AGE = Pattern.compile("\\b(\\d{1,2})\\s*\\+?\\s*([a-zа-я]{1,8})\\b");

    private record Words(Set<String> exact, List<String> stems) {
        boolean matches(String token) {
            if (token.isEmpty()) {
                return false;
            }
            if (exact.contains(token)) {
                return true;
            }
            String collapsed = collapse(token);
            for (String e : exact) {
                String ce = collapse(e);
                if (ce.length() >= 3 && ce.equals(collapsed)) {
                    return true;
                }
            }
            for (String s : stems) {
                if (token.startsWith(s) || (collapse(s).length() >= 3 && collapsed.startsWith(collapse(s)))) {
                    return true;
                }
            }
            return false;
        }

        boolean matchesWildcard(Pattern wildcard) {
            for (String e : exact) {
                if (wildcard.matcher(e).matches()) {
                    return true;
                }
            }
            return false;
        }
    }

    private final Words sexual;
    private final Words profanity;
    private final Words hate;
    private final Words minors;
    private final Set<String> ageWords = new HashSet<>();
    private final Set<String> allowWords = new HashSet<>();
    private final List<String> allowPhrases = new ArrayList<>();
    private final List<Pattern> hatePatterns = new ArrayList<>();

    public AbuseGuard(Map<String, JsonNode> packs) {
        Map<String, Set<String>> exact = new HashMap<>();
        Map<String, List<String>> stems = new HashMap<>();
        for (JsonNode pack : packs.values()) {
            JsonNode abuse = pack.path("abuse");
            for (String category : List.of("sexual", "profanity", "hate", "minors")) {
                for (JsonNode v : abuse.path(category).path("exact")) {
                    exact.computeIfAbsent(category, k -> new HashSet<>()).add(word(v.asText("")));
                }
                for (JsonNode v : abuse.path(category).path("stems")) {
                    stems.computeIfAbsent(category, k -> new ArrayList<>()).add(word(v.asText("")));
                }
            }
            for (JsonNode v : abuse.path("minors").path("ageWords")) {
                ageWords.add(word(v.asText("")));
            }
            for (JsonNode v : abuse.path("allow").path("words")) {
                allowWords.add(word(v.asText("")));
            }
            for (JsonNode v : abuse.path("allow").path("phrases")) {
                allowPhrases.add(TextNormalizer.normalizedPhrase(v.asText("")));
            }
            for (JsonNode v : abuse.path("patterns")) {
                hatePatterns.add(Pattern.compile("(?iU)" + v.asText()));
            }
        }
        this.sexual = words(exact, stems, "sexual");
        this.profanity = words(exact, stems, "profanity");
        this.hate = words(exact, stems, "hate");
        this.minors = words(exact, stems, "minors");
    }

    private static Words words(Map<String, Set<String>> exact, Map<String, List<String>> stems, String category) {
        Set<String> e = new HashSet<>(exact.getOrDefault(category, Set.of()));
        e.remove("");
        List<String> s = new ArrayList<>();
        for (String v : stems.getOrDefault(category, List.of())) {
            if (!v.isEmpty()) {
                s.add(v);
            }
        }
        return new Words(e, s);
    }

    private static String word(String value) {
        return TextNormalizer.fold(value).trim();
    }

    static String collapse(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        char prev = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != prev) {
                sb.append(c);
            }
            prev = c;
        }
        return sb.toString();
    }

    static String leet(String token) {
        StringBuilder sb = new StringBuilder(token.length());
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            Character m = LEET.get(c);
            sb.append(m != null && (token.length() > 1) ? m : c);
        }
        return sb.toString();
    }

    private static String map(String token, Map<Character, Character> table) {
        StringBuilder sb = new StringBuilder(token.length());
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            Character m = table.get(c);
            sb.append(m != null ? m : c);
        }
        return sb.toString();
    }

    private static boolean hasCyrillic(String token) {
        for (int i = 0; i < token.length(); i++) {
            if (TextNormalizer.isCyrillic(token.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLatin(String token) {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c >= 'a' && c <= 'z') {
                return true;
            }
        }
        return false;
    }

    public List<String> tokens(String text) {
        String folded = TextNormalizer.fold(text == null ? "" : text).toLowerCase(Locale.ROOT);
        String phrase = " " + folded.replaceAll("\\s+", " ").trim() + " ";
        String plain = TextNormalizer.normalizedPhrase(folded);
        for (String allowed : allowPhrases) {
            if (plain.contains(allowed)) {
                plain = plain.replace(allowed, " ");
                for (String w : allowed.trim().split(" ")) {
                    phrase = phrase.replaceAll("(?U)(?<![\\p{L}\\d])" + Pattern.quote(w) + "(?![\\p{L}\\d])", " ");
                }
            }
        }
        List<String> raw = new ArrayList<>();
        for (String part : SPLIT.split(phrase)) {
            if (part.isBlank()) {
                continue;
            }
            String t = leet(part.trim());
            t = SEPARATORS.matcher(t).replaceAll("");
            t = t.replaceAll("[^\\p{L}\\d*]", "");
            if (!t.isEmpty()) {
                raw.add(t);
            }
        }
        List<String> out = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        int runCount = 0;
        for (String t : raw) {
            if (t.length() == 1 && Character.isLetter(t.charAt(0))) {
                run.append(t);
                runCount++;
                continue;
            }
            if (runCount >= 3) {
                out.add(run.toString());
            } else if (runCount > 0) {
                for (int i = 0; i < run.length(); i++) {
                    out.add(String.valueOf(run.charAt(i)));
                }
            }
            run.setLength(0);
            runCount = 0;
            out.add(t);
        }
        if (runCount >= 3) {
            out.add(run.toString());
        } else {
            for (int i = 0; i < run.length(); i++) {
                out.add(String.valueOf(run.charAt(i)));
            }
        }
        return out;
    }

    private boolean matches(Words words, String token) {
        String bare = token.replace("*", "");
        if (allowWords.contains(bare) || allowWords.contains(collapse(bare))) {
            return false;
        }
        if (token.contains("*")) {
            if (bare.length() < 2) {
                return false;
            }
            StringBuilder regex = new StringBuilder();
            for (int i = 0; i < token.length(); i++) {
                char c = token.charAt(i);
                regex.append(c == '*' ? "\\p{L}{0,2}" : Pattern.quote(String.valueOf(c)));
            }
            Pattern wildcard = Pattern.compile(regex.toString());
            return words.matchesWildcard(wildcard)
                    || words.matchesWildcard(Pattern.compile(map(regex.toString(), CYR_TO_LAT)));
        }
        if (words.matches(bare)) {
            return true;
        }
        if (hasCyrillic(bare) && hasLatin(bare)) {
            return words.matches(map(bare, LAT_TO_CYR)) || words.matches(map(bare, CYR_TO_LAT));
        }
        if (hasLatin(bare)) {
            String cyr = map(bare, LAT_TO_CYR);
            return !cyr.equals(bare) && !hasLatin(cyr) && words.matches(cyr) && bare.length() >= 4;
        }
        return false;
    }

    public Category inspect(String text) {
        if (text == null || text.isBlank()) {
            return Category.NONE;
        }
        String phrase = TextNormalizer.normalizedPhrase(TextNormalizer.fold(text));
        for (Pattern p : hatePatterns) {
            if (p.matcher(phrase).find()) {
                return Category.HATE;
            }
        }
        List<String> tokens = tokens(text);
        boolean isSexual = false;
        boolean isProfane = false;
        boolean isHate = false;
        boolean minor = false;
        for (String t : tokens) {
            if (matches(hate, t)) {
                isHate = true;
            }
            if (matches(sexual, t)) {
                isSexual = true;
            }
            if (matches(profanity, t)) {
                isProfane = true;
            }
            if (minors.matches(t.replace("*", ""))) {
                minor = true;
            }
        }
        if (isSexual && !minor) {
            Matcher m = AGE.matcher(phrase);
            while (m.find()) {
                int age = Integer.parseInt(m.group(1));
                if (age < 18 && ageWords.contains(m.group(2))) {
                    minor = true;
                }
            }
        }
        if (isSexual && minor) {
            return Category.SEXUAL_MINORS;
        }
        if (isHate) {
            return Category.HATE;
        }
        if (isSexual) {
            return Category.SEXUAL;
        }
        if (isProfane) {
            return Category.PROFANITY;
        }
        return Category.NONE;
    }

    public boolean isSexualTitle(String title) {
        if (title == null || title.isBlank()) {
            return false;
        }
        for (String t : tokens(title)) {
            if (matches(sexual, t)) {
                return true;
            }
        }
        return false;
    }
}
