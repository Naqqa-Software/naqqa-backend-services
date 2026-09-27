package com.naqqa.elasticsearch.analysis.phonetic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KoelnerPhonetik implements PhoneticEncoder {

    private static final String[] POSTEL_VARIATIONS_PATTERNS = {"AUN", "OWN", "RB", "RW", "WSK", "RSK"};
    private static final String[] POSTEL_VARIATIONS_REPLACEMENTS = {"OWN", "AUN", "RW", "RB", "RSK", "WSK"};

    private static final Set<Character> CSZ = new HashSet<>(Arrays.asList('C', 'S', 'Z'));
    private static final Set<Character> CKQ = new HashSet<>(Arrays.asList('C', 'K', 'Q'));
    private static final Set<Character> AOUHKXQ = new HashSet<>(Arrays.asList('A', 'O', 'U', 'H', 'K', 'X', 'Q'));
    private static final Set<Character> AHKLOQRUX = new HashSet<>(Arrays.asList('A', 'H', 'K', 'L', 'O', 'Q', 'R', 'U', 'X'));

    private final Pattern[] variationsPatterns;
    private final boolean primary;

    public KoelnerPhonetik() {
        this(false);
    }

    public KoelnerPhonetik(boolean useOnlyPrimaryCode) {
        this.primary = useOnlyPrimaryCode;
        String[] patterns = getPatterns();
        this.variationsPatterns = new Pattern[patterns.length];
        for (int i = 0; i < patterns.length; i++) {
            this.variationsPatterns[i] = Pattern.compile(patterns[i]);
        }
    }

    protected String[] getPatterns() {
        return POSTEL_VARIATIONS_PATTERNS;
    }

    protected String[] getReplacements() {
        return POSTEL_VARIATIONS_REPLACEMENTS;
    }

    protected char getCode() {
        return '0';
    }

    @Override
    public String encode(String input) {
        if (input == null) {
            return null;
        }
        String[] s = code(input);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length; i++) {
            sb.append(s[i]);
            if (i < s.length - 1) {
                sb.append('_');
            }
        }
        return SoundexUtils.emptyToNull(sb.toString());
    }

    public double getRelativeValue(Object o1, Object o2) {
        String[] kopho1 = code(expandUmlauts(o1.toString().toUpperCase(Locale.GERMANY)));
        String[] kopho2 = code(expandUmlauts(o2.toString().toUpperCase(Locale.GERMANY)));
        for (String a : kopho1) {
            for (String b : kopho2) {
                if (a.equals(b)) {
                    return 1;
                }
            }
        }
        return 0;
    }

    private String[] code(String str) {
        List<String> parts = partition(str);
        String[] codes = new String[parts.size()];
        int i = 0;
        for (String s : parts) {
            codes[i++] = substitute(s);
        }
        return codes;
    }

    private List<String> partition(String str) {
        List<String> parts = new ArrayList<>();
        parts.add(str.replaceAll("[^\\p{L}\\p{N}]", ""));
        if (!primary) {
            List<String> tmpParts = new ArrayList<>(Arrays.asList(str.split("[\\p{Z}\\p{C}\\p{P}]")));
            int numberOfParts = tmpParts.size();
            while (!tmpParts.isEmpty()) {
                StringBuilder part = new StringBuilder();
                for (int i = 0; i < tmpParts.size(); i++) {
                    part.append(tmpParts.get(i));
                    if (i + 1 != numberOfParts) {
                        parts.add(part.toString());
                    }
                }
                tmpParts.remove(0);
            }
        }
        List<String> variations = new ArrayList<>();
        for (String part : parts) {
            List<String> variation = getVariations(part);
            if (variation != null) {
                variations.addAll(variation);
            }
        }
        return variations;
    }

    private List<String> getVariations(String str) {
        String[] patterns = getPatterns();
        String[] replacements = getReplacements();
        int position = 0;
        List<String> variations = new ArrayList<>();
        variations.add("");
        while (position < str.length()) {
            int i = 0;
            int substPos = -1;
            while (substPos < position && i < patterns.length) {
                Matcher m = variationsPatterns[i].matcher(str);
                while (substPos < position && m.find()) {
                    substPos = m.start();
                }
                i++;
            }
            if (substPos >= position) {
                i--;
                List<String> varNew = new ArrayList<>();
                String prevPart = str.substring(position, substPos);
                for (int ii = 0; ii < variations.size(); ii++) {
                    String tmp = variations.get(ii);
                    varNew.add(tmp.concat(prevPart + replacements[i]));
                    variations.set(ii, variations.get(ii) + prevPart + patterns[i]);
                }
                variations.addAll(varNew);
                position = substPos + patterns[i].length();
            } else {
                for (int ii = 0; ii < variations.size(); ii++) {
                    variations.set(ii, variations.get(ii) + str.substring(position));
                }
                position = str.length();
            }
        }
        return variations;
    }

    private String substitute(String str) {
        String s = expandUmlauts(str.toUpperCase(Locale.GERMAN));
        s = removeSequences(s);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char current = s.charAt(i);
            char next = i + 1 < s.length() ? s.charAt(i + 1) : '_';
            char prev = i > 0 ? s.charAt(i - 1) : '_';
            switch (current) {
                case 'A':
                case 'E':
                case 'I':
                case 'J':
                case 'Y':
                case 'O':
                case 'U':
                    if (i == 0 || (i == 1 && prev == 'H')) {
                        sb.append(getCode());
                    }
                    break;
                case 'P':
                    sb.append(next == 'H' ? "33" : "1");
                    break;
                case 'B':
                    sb.append('1');
                    break;
                case 'D':
                case 'T':
                    sb.append(CSZ.contains(next) ? '8' : '2');
                    break;
                case 'F':
                case 'V':
                case 'W':
                    sb.append('3');
                    break;
                case 'G':
                case 'K':
                case 'Q':
                    sb.append('4');
                    break;
                case 'C':
                    if (i == 0) {
                        sb.append(AHKLOQRUX.contains(next) ? '4' : '8');
                    } else {
                        sb.append(AOUHKXQ.contains(next) ? '4' : '8');
                    }
                    if (sb.length() >= 2 && sb.charAt(sb.length() - 2) == '8') {
                        sb.setCharAt(sb.length() - 1, '8');
                    }
                    break;
                case 'X':
                    sb.append(i < 1 || !CKQ.contains(prev) ? "48" : "8");
                    break;
                case 'L':
                    sb.append('5');
                    break;
                case 'M':
                case 'N':
                    sb.append('6');
                    break;
                case 'R':
                    sb.append('7');
                    break;
                case 'S':
                case 'Z':
                    sb.append('8');
                    break;
                default:
                    break;
            }
        }
        return removeSequences(sb.toString());
    }

    private static String expandUmlauts(String str) {
        return str.replace("Ä", "AE").replace("Ö", "OE").replace("Ü", "UE");
    }

    private static String removeSequences(String str) {
        if (str == null || str.isEmpty()) {
            return "";
        }
        int i = 0;
        int j = 0;
        StringBuilder sb = new StringBuilder().append(str.charAt(i++));
        while (i < str.length()) {
            char c = str.charAt(i);
            if (c != sb.charAt(j)) {
                sb.append(c);
                j++;
            }
            i++;
        }
        return sb.toString();
    }
}
