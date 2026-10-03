package com.naqqa.chatbot.ai;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class TextNormalizer {

    private static final Map<Character, String> TRANSLIT = Map.ofEntries(
            Map.entry('а', "a"), Map.entry('б', "b"), Map.entry('в', "v"), Map.entry('г', "g"),
            Map.entry('д', "d"), Map.entry('е', "e"), Map.entry('ж', "j"), Map.entry('з', "z"),
            Map.entry('и', "i"), Map.entry('к', "k"), Map.entry('л', "l"), Map.entry('м', "m"),
            Map.entry('н', "n"), Map.entry('о', "o"), Map.entry('п', "p"), Map.entry('р', "r"),
            Map.entry('с', "s"), Map.entry('т', "t"), Map.entry('у', "u"), Map.entry('ф', "f"),
            Map.entry('х', "h"), Map.entry('ц', "ts"), Map.entry('ч', "ch"), Map.entry('ш', "sh"),
            Map.entry('щ', "sch"), Map.entry('ъ', ""), Map.entry('ы', "y"), Map.entry('ь', ""),
            Map.entry('э', "e"), Map.entry('ю', "iu"), Map.entry('я', "ia"));

    private TextNormalizer() {
    }

    public static String clean(String value) {
        if (value == null) {
            return "";
        }
        String s = Normalizer.normalize(value, Normalizer.Form.NFC)
                .replace('ş', 'ș').replace('Ş', 'Ș').replace('ţ', 'ț').replace('Ţ', 'Ț');
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\t' || c == '\r') {
                sb.append(' ');
            } else if (!Character.isISOControl(c) && Character.getType(c) != Character.FORMAT) {
                sb.append(c);
            }
        }
        return sb.toString().replaceAll("\\s{2,}", " ").trim();
    }

    public static String fold(String value) {
        if (value == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (Character.getType(c) == Character.NON_SPACING_MARK) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString().toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    public static List<String> tokens(String value) {
        List<String> out = new ArrayList<>();
        String folded = fold(value);
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < folded.length(); i++) {
            char c = folded.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                current.append(c);
            } else if (current.length() > 0) {
                out.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    public static String normalizedPhrase(String value) {
        return " " + String.join(" ", tokens(value)) + " ";
    }

    public static boolean isCyrillic(char c) {
        return Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC;
    }

    public static String transliterate(String folded) {
        StringBuilder sb = new StringBuilder(folded.length());
        for (int i = 0; i < folded.length(); i++) {
            char c = folded.charAt(i);
            String t = TRANSLIT.get(c);
            sb.append(t != null ? t : String.valueOf(c));
        }
        return sb.toString();
    }

    public static String compact(String value) {
        StringBuilder sb = new StringBuilder();
        for (String t : tokens(value)) {
            sb.append(t);
        }
        return sb.toString();
    }

    public static int levenshtein(String a, String b, int max) {
        if (Math.abs(a.length() - b.length()) > max) {
            return max + 1;
        }
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, cur[j]);
            }
            if (rowMin > max) {
                return max + 1;
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }
}
