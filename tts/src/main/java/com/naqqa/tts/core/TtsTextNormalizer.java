package com.naqqa.tts.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TtsTextNormalizer {

    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]*)]\\((?:[^)]*)\\)");
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");
    private static final Pattern EMAIL = Pattern.compile("\\S+@\\S+\\.\\w+");
    private static final Pattern PATH = Pattern.compile("(?<=\\s|^)/[\\w\\-/]+");
    private static final Pattern MD_MARKS = Pattern.compile("(\\*\\*|__|`+|~~|^#{1,6}\\s+|^>\\s*)", Pattern.MULTILINE);
    private static final Pattern BULLET = Pattern.compile("^\\s*(?:[-*•▪◦]|\\d+[.)])\\s+", Pattern.MULTILINE);
    private static final Pattern EMOJI = Pattern.compile("[\\p{So}\\p{Cn}\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]");
    private static final Pattern PRICE = Pattern.compile("(?<![\\d,.])(\\d{1,7})(?:[,.](\\d{1,2}))?\\s*(?:lei|leu|MDL|mdl|лей|лея)(?![\\p{L}])");
    private static final Pattern PERCENT = Pattern.compile("(?<![\\d,.])([-−–]?)\\s?(\\d{1,3}(?:[,.]\\d+)?)\\s?%");
    private static final Pattern DECIMAL = Pattern.compile("(?<![\\d,.])(\\d+),(\\d+)(?![\\d,.])");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern LINE_BREAKS = Pattern.compile("\\s*\\n+\\s*");

    private static final Map<String, Map<String, String>> ABBREVIATIONS = Map.of(
            "ro", ordered(
                    "buc.", "bucăți",
                    "nr.", "numărul",
                    "str.", "strada",
                    "bd.", "bulevardul",
                    "ex.", "de exemplu",
                    "etc.", "etcetera",
                    "tel.", "telefon",
                    "kg", "kilograme",
                    "ml", "mililitri",
                    "gr.", "grame",
                    "min.", "minute"),
            "ru", ordered(
                    "шт.", "штук",
                    "ул.", "улица",
                    "т.д.", "так далее",
                    "т.е.", "то есть",
                    "напр.", "например",
                    "кг", "килограмм",
                    "мл", "миллилитров",
                    "тел.", "телефон",
                    "мин.", "минут"),
            "en", ordered(
                    "e.g.", "for example",
                    "etc.", "et cetera",
                    "pcs", "pieces",
                    "kg", "kilograms",
                    "ml", "milliliters"));

    public String normalize(String text, String lang, Map<String, String> lexicon, int maxChars) {
        if (text == null) {
            return "";
        }
        String l = lang == null ? "ro" : lang.toLowerCase();
        String s = text.replace("\r", "");
        s = MD_LINK.matcher(s).replaceAll("$1");
        s = URL.matcher(s).replaceAll(" ");
        s = EMAIL.matcher(s).replaceAll(" ");
        s = PATH.matcher(s).replaceAll(" ");
        s = MD_MARKS.matcher(s).replaceAll("");
        s = BULLET.matcher(s).replaceAll("");
        s = EMOJI.matcher(s).replaceAll("");
        s = LINE_BREAKS.matcher(s).replaceAll(". ");
        s = s.replaceAll("\\.\\s*\\.", ".").replaceAll("([!?:;])\\s*\\.", "$1");
        s = prices(s, l);
        s = percents(s, l);
        s = decimals(s, l);
        s = abbreviations(s, ABBREVIATIONS.getOrDefault(l, Map.of()));
        s = lexicon(s, lexicon);
        s = SPACES.matcher(s).replaceAll(" ").trim();
        s = s.replaceAll("\\s+([,.!?;:])", "$1");
        return truncate(s, maxChars);
    }

    static String truncate(String s, int maxChars) {
        if (maxChars <= 0 || s.length() <= maxChars) {
            return s;
        }
        String cut = s.substring(0, maxChars);
        int sentence = Math.max(cut.lastIndexOf(". "), Math.max(cut.lastIndexOf("! "), cut.lastIndexOf("? ")));
        if (sentence > 0) {
            return cut.substring(0, sentence + 1).trim();
        }
        int space = cut.lastIndexOf(' ');
        return (space > maxChars / 2 ? cut.substring(0, space) : cut).trim();
    }

    private static String prices(String s, String lang) {
        Matcher m = PRICE.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            long lei = Long.parseLong(m.group(1));
            String raw = m.group(2);
            int bani = raw == null ? 0 : Integer.parseInt(raw.length() == 1 ? raw + "0" : raw);
            m.appendReplacement(out, Matcher.quoteReplacement(spokenPrice(lei, bani, lang)));
        }
        m.appendTail(out);
        return out.toString();
    }

    static String spokenPrice(long lei, int bani, String lang) {
        if ("ru".equals(lang)) {
            String main = lei + " " + ruPlural(lei, "лей", "лея", "лей");
            return bani == 0 ? main : main + " " + bani + " " + ruPlural(bani, "бан", "бана", "бань");
        }
        if ("en".equals(lang)) {
            String main = lei + (lei == 1 ? " leu" : " lei");
            return bani == 0 ? main : main + " " + bani + (bani == 1 ? " ban" : " bani");
        }
        String main = roCount(lei, "leu", "lei");
        return bani == 0 ? main : main + " și " + roCount(bani, "ban", "bani");
    }

    static String roCount(long n, String singular, String plural) {
        if (n == 1) {
            return "un " + singular;
        }
        long rest = n % 100;
        boolean de = n != 0 && (rest == 0 || rest >= 20);
        return n + (de ? " de " : " ") + plural;
    }

    static String ruPlural(long n, String one, String few, String many) {
        long mod100 = n % 100;
        long mod10 = n % 10;
        if (mod100 >= 11 && mod100 <= 14) {
            return many;
        }
        if (mod10 == 1) {
            return one;
        }
        if (mod10 >= 2 && mod10 <= 4) {
            return few;
        }
        return many;
    }

    private static String percents(String s, String lang) {
        Matcher m = PERCENT.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            boolean minus = !m.group(1).isEmpty();
            String number = m.group(2);
            String spoken;
            if ("ru".equals(lang)) {
                long whole = number.contains(",") || number.contains(".") ? 5 : Long.parseLong(number);
                spoken = (minus ? "минус " : "") + number.replace('.', ',') + " " + ruPlural(whole, "процент", "процента", "процентов");
            } else if ("en".equals(lang)) {
                spoken = (minus ? "minus " : "") + number + " percent";
            } else {
                spoken = (minus ? "minus " : "") + number + " la sută";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(" " + spoken));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String decimals(String s, String lang) {
        String word = switch (lang) {
            case "ru" -> " целых ";
            case "en" -> " point ";
            default -> " virgulă ";
        };
        return DECIMAL.matcher(s).replaceAll("$1" + Matcher.quoteReplacement(word) + "$2");
    }

    private static String abbreviations(String s, Map<String, String> map) {
        String out = s;
        for (Map.Entry<String, String> e : map.entrySet()) {
            String key = e.getKey();
            String regex = "(?<![\\p{L}])" + Pattern.quote(key) + (key.endsWith(".") ? "" : "(?![\\p{L}\\d])");
            Pattern p = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            out = p.matcher(out).replaceAll(Matcher.quoteReplacement(" " + e.getValue()));
        }
        return out;
    }

    private static String lexicon(String s, Map<String, String> lexicon) {
        if (lexicon == null || lexicon.isEmpty()) {
            return s;
        }
        List<Map.Entry<String, String>> entries = new ArrayList<>(lexicon.entrySet());
        entries.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed());
        String out = s;
        for (Map.Entry<String, String> e : entries) {
            if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null) {
                continue;
            }
            Pattern p = Pattern.compile("(?<![\\p{L}\\d])" + Pattern.quote(e.getKey()) + "(?![\\p{L}\\d])",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            out = p.matcher(out).replaceAll(Matcher.quoteReplacement(e.getValue()));
        }
        return out;
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
