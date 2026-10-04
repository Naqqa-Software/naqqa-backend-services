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
    private static final Pattern PHONE = Pattern.compile("(?<![\\p{L}\\d,.])(?:\\+\\d|0)(?:[ \\-]?\\d){5,13}(?!\\d|[,.]\\d)");
    private static final Pattern PHONE_GROUP = Pattern.compile("[ \\-]+");
    private static final Pattern PRICE = Pattern.compile("(?<![\\d,.])(\\d{1,7})(?:[,.](\\d{1,2}))?\\s*(?:lei|leu|MDL|mdl|лей|лея)(?![\\p{L}])");
    private static final Pattern PERCENT = Pattern.compile("(?<![\\d,.])([-−–]?)\\s?(\\d{1,3}(?:[,.]\\d+)?)\\s?%");
    private static final Pattern DECIMAL = Pattern.compile("(?<![\\d,.])(\\d+),(\\d+)(?![\\d,.])");
    private static final Pattern RANGE = Pattern.compile("(?<![\\d\\-+,.])(\\d{1,4})\\s?[-–]\\s?(\\d{1,4})(?![\\d\\-,.])");
    private static final Pattern PER_UNIT = Pattern.compile("\\s?/\\s?(kg|кг|l|л|buc|шт)(?:\\.(?!\\s+(?-i:\\p{Lu})))?(?![\\p{L}\\d])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern UNIT = Pattern.compile("(?<![\\p{L}\\d,.])(\\d{1,5})(?:[,.](\\d{1,3}))?\\s?(kg|ml|gr|buc|l|g|кг|мл|шт|л|г)(?:\\.(?!\\s+(?-i:\\p{Lu})))?(?![\\p{L}\\d])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern DATE = Pattern.compile("(?<![\\d,.])(\\d{2})\\.(\\d{2})(?:\\.(\\d{4}))?(?!\\d|[,.]\\d)");
    private static final Pattern CAPS_WORD = Pattern.compile("(?<![\\p{L}\\d])[\\p{Lu}]{4,}(?![\\p{L}\\d])");
    private static final Pattern LATIN_WORD = Pattern.compile("(?<![\\p{L}\\d])[A-Za-zĂÂÎȘȚŞŢăâîșțşţ]+(?![\\p{L}\\d])");

    private static final String[] RO_MONTHS = {"ianuarie", "februarie", "martie", "aprilie", "mai", "iunie", "iulie", "august", "septembrie", "octombrie", "noiembrie", "decembrie"};
    private static final String[] RU_MONTHS = {"января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"};
    private static final String[][] TRANSLIT = {
            {"sch", "ш"}, {"che", "ке"}, {"chi", "ки"}, {"ghe", "ге"}, {"ghi", "ги"}, {"sh", "ш"}, {"ch", "ч"}, {"ce", "че"}, {"ci", "чи"},
            {"ge", "дже"}, {"gi", "джи"}, {"ph", "ф"}, {"th", "т"}, {"ee", "и"}, {"oo", "у"}, {"ou", "у"}, {"qu", "кв"}, {"ck", "к"},
            {"a", "а"}, {"b", "б"}, {"c", "к"}, {"d", "д"}, {"e", "е"}, {"f", "ф"}, {"g", "г"}, {"h", "х"}, {"i", "и"}, {"j", "ж"},
            {"k", "к"}, {"l", "л"}, {"m", "м"}, {"n", "н"}, {"o", "о"}, {"p", "п"}, {"q", "к"}, {"r", "р"}, {"s", "с"}, {"t", "т"},
            {"u", "у"}, {"v", "в"}, {"w", "в"}, {"x", "кс"}, {"y", "и"}, {"z", "з"}, {"ă", "э"}, {"â", "ы"}, {"î", "ы"}, {"ș", "ш"},
            {"ş", "ш"}, {"ț", "ц"}, {"ţ", "ц"}};
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
                    "kg", "kilogram",
                    "ml", "mililitri",
                    "gr.", "grame",
                    "min.", "minute"),
            "ru", ordered(
                    "шт.", "штук",
                    "на ул.", "на улице",
                    "по ул.", "по улице",
                    "ул.", "улица",
                    "бул.", "бульвар",
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
        s = phones(s, l);
        s = prices(s, l);
        s = perUnit(s, l);
        s = ranges(s, l);
        s = units(s, l);
        s = dates(s, l);
        s = percents(s, l);
        s = decimals(s, l);
        s = abbreviations(s, ABBREVIATIONS.getOrDefault(l, Map.of()));
        s = lexicon(s, lexicon);
        s = s.replace("&", " " + switch (l) {
            case "ru" -> "и";
            case "en" -> "and";
            default -> "și";
        } + " ");
        s = capitals(s);
        if ("ru".equals(l)) {
            s = cyrillize(s);
        }
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

    private static String phones(String s, String lang) {
        Matcher m = PHONE.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String raw = m.group();
            StringBuilder spoken = new StringBuilder();
            if (raw.startsWith("+")) {
                spoken.append("ru".equals(lang) ? "плюс " : "plus ");
                raw = raw.substring(1);
            }
            List<String> groups = new ArrayList<>();
            for (String group : PHONE_GROUP.split(raw)) {
                groups.add(String.join(" ", group.split("")));
            }
            spoken.append(String.join(", ", groups));
            m.appendReplacement(out, Matcher.quoteReplacement(spoken.toString()));
        }
        m.appendTail(out);
        return out.toString();
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
                long whole = number.contains(",") || number.contains(".") ? 2 : Long.parseLong(number);
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
        if ("ru".equals(lang)) {
            Matcher m = DECIMAL.matcher(s);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                String fraction = m.group(2);
                String unit = switch (fraction.length()) {
                    case 1 -> "десятых";
                    case 2 -> "сотых";
                    default -> "тысячных";
                };
                String whole = "1".equals(m.group(1)) ? "одна целая" : m.group(1) + " целых";
                int part = Integer.parseInt(fraction);
                String tail = part == 1 ? "одна " + unit.replace("ых", "ая") : part + " " + unit;
                m.appendReplacement(out, Matcher.quoteReplacement(whole + " " + tail));
            }
            m.appendTail(out);
            return out.toString();
        }
        String word = "en".equals(lang) ? " point " : " virgulă ";
        return DECIMAL.matcher(s).replaceAll("$1" + Matcher.quoteReplacement(word) + "$2");
    }

    private static String perUnit(String s, String lang) {
        Matcher m = PER_UNIT.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String unit = m.group(1).toLowerCase();
            String spoken;
            if ("ru".equals(lang)) {
                spoken = switch (unit) {
                    case "kg", "кг" -> " за килограмм";
                    case "l", "л" -> " за литр";
                    default -> " за штуку";
                };
            } else if ("en".equals(lang)) {
                spoken = switch (unit) {
                    case "kg", "кг" -> " per kilogram";
                    case "l", "л" -> " per liter";
                    default -> " per piece";
                };
            } else {
                spoken = switch (unit) {
                    case "kg", "кг" -> " pe kilogram";
                    case "l", "л" -> " pe litru";
                    default -> " pe bucată";
                };
            }
            m.appendReplacement(out, Matcher.quoteReplacement(spoken));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String ranges(String s, String lang) {
        String word = switch (lang) {
            case "ru" -> " до ";
            case "en" -> " to ";
            default -> " până la ";
        };
        return RANGE.matcher(s).replaceAll("$1" + Matcher.quoteReplacement(word) + "$2");
    }

    private static String units(String s, String lang) {
        Matcher m = UNIT.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String unit = m.group(3).toLowerCase();
            String whole = m.group(1);
            String fraction = m.group(2);
            if ("ru".equals(lang) && "г".equals(unit) && fraction == null && whole.length() == 4) {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
                continue;
            }
            String number = fraction == null ? whole : whole + "," + fraction;
            m.appendReplacement(out, Matcher.quoteReplacement(spokenUnit(number, Long.parseLong(whole), fraction != null, unit, lang)));
        }
        m.appendTail(out);
        return out.toString();
    }

    static String spokenUnit(String number, long whole, boolean decimal, String unit, String lang) {
        String key = switch (unit) {
            case "kg", "кг" -> "kg";
            case "ml", "мл" -> "ml";
            case "g", "gr", "г" -> "g";
            case "buc", "шт" -> "buc";
            default -> "l";
        };
        if ("ru".equals(lang)) {
            String[] forms = switch (key) {
                case "kg" -> new String[]{"килограмм", "килограмма", "килограммов"};
                case "ml" -> new String[]{"миллилитр", "миллилитра", "миллилитров"};
                case "g" -> new String[]{"грамм", "грамма", "граммов"};
                case "buc" -> new String[]{"штука", "штуки", "штук"};
                default -> new String[]{"литр", "литра", "литров"};
            };
            return number + " " + (decimal ? forms[1] : ruPlural(whole, forms[0], forms[1], forms[2]));
        }
        if ("en".equals(lang)) {
            String[] forms = switch (key) {
                case "kg" -> new String[]{"kilogram", "kilograms"};
                case "ml" -> new String[]{"milliliter", "milliliters"};
                case "g" -> new String[]{"gram", "grams"};
                case "buc" -> new String[]{"piece", "pieces"};
                default -> new String[]{"liter", "liters"};
            };
            return number + " " + (!decimal && whole == 1 ? forms[0] : forms[1]);
        }
        String[] forms = switch (key) {
            case "kg" -> new String[]{"kilogram", "kilograme", "un"};
            case "ml" -> new String[]{"mililitru", "mililitri", "un"};
            case "g" -> new String[]{"gram", "grame", "un"};
            case "buc" -> new String[]{"bucată", "bucăți", "o"};
            default -> new String[]{"litru", "litri", "un"};
        };
        if (decimal) {
            return number + " " + forms[1];
        }
        if (whole == 1) {
            return forms[2] + " " + forms[0];
        }
        return roCount(whole, forms[0], forms[1]);
    }

    private static String dates(String s, String lang) {
        if (!"ro".equals(lang) && !"ru".equals(lang)) {
            return s;
        }
        Matcher m = DATE.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            int day = Integer.parseInt(m.group(1));
            int month = Integer.parseInt(m.group(2));
            if (day < 1 || day > 31 || month < 1 || month > 12) {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
                continue;
            }
            String name = "ru".equals(lang) ? RU_MONTHS[month - 1] : RO_MONTHS[month - 1];
            String year = m.group(3) == null ? "" : " " + m.group(3);
            m.appendReplacement(out, Matcher.quoteReplacement(day + " " + name + year));
        }
        m.appendTail(out);
        return out.toString();
    }

    static String capitals(String s) {
        Matcher m = CAPS_WORD.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(m.group().toLowerCase()));
        }
        m.appendTail(out);
        return out.toString();
    }

    static String cyrillize(String s) {
        Matcher m = LATIN_WORD.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(transliterate(m.group())));
        }
        m.appendTail(out);
        return out.toString();
    }

    static String transliterate(String word) {
        String lower = word.toLowerCase();
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < lower.length()) {
            boolean matched = false;
            for (String[] pair : TRANSLIT) {
                if (lower.startsWith(pair[0], i)) {
                    out.append(pair[1]);
                    i += pair[0].length();
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                out.append(lower.charAt(i));
                i++;
            }
        }
        if (word.length() > 1 && word.equals(word.toUpperCase())) {
            return out.toString().toUpperCase();
        }
        if (out.length() > 0 && Character.isUpperCase(word.charAt(0))) {
            out.setCharAt(0, Character.toUpperCase(out.charAt(0)));
        }
        return out.toString();
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
