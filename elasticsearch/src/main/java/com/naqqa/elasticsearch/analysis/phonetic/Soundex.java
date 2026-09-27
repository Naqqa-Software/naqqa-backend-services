package com.naqqa.elasticsearch.analysis.phonetic;

public class Soundex implements PhoneticEncoder {

    public static final char SILENT_MARKER = '-';
    public static final String US_ENGLISH_MAPPING_STRING = "01230120022455012623010202";
    public static final String US_ENGLISH_GENEALOGY_MAPPING_STRING = "-123-12--22455-12623-1-2-2";

    private final char[] soundexMapping;
    private final boolean specialCaseHW;

    public Soundex() {
        this.soundexMapping = US_ENGLISH_MAPPING_STRING.toCharArray();
        this.specialCaseHW = true;
    }

    public Soundex(String mapping) {
        this.soundexMapping = mapping.toCharArray();
        this.specialCaseHW = !hasMarker(this.soundexMapping);
    }

    public Soundex(String mapping, boolean specialCaseHW) {
        this.soundexMapping = mapping.toCharArray();
        this.specialCaseHW = specialCaseHW;
    }

    public static Soundex simplified() {
        return new Soundex(US_ENGLISH_MAPPING_STRING, false);
    }

    public static Soundex genealogy() {
        return new Soundex(US_ENGLISH_GENEALOGY_MAPPING_STRING);
    }

    @Override
    public String encode(String input) {
        return SoundexUtils.emptyToNull(soundex(input));
    }

    public int difference(String s1, String s2) {
        return SoundexUtils.differenceEncoded(encode(s1), encode(s2));
    }

    private static boolean hasMarker(char[] mapping) {
        for (char ch : mapping) {
            if (ch == SILENT_MARKER) {
                return true;
            }
        }
        return false;
    }

    private char map(char ch) {
        int index = ch - 'A';
        if (index < 0 || index >= soundexMapping.length) {
            throw new IllegalArgumentException("The character is not mapped: " + ch + " (index=" + index + ")");
        }
        return soundexMapping[index];
    }

    private String soundex(String input) {
        if (input == null) {
            return null;
        }
        String str = SoundexUtils.clean(input);
        if (str.isEmpty()) {
            return str;
        }
        char[] out = {'0', '0', '0', '0'};
        int count = 0;
        char first = str.charAt(0);
        out[count++] = first;
        char lastDigit = map(first);
        for (int i = 1; i < str.length() && count < out.length; i++) {
            char ch = str.charAt(i);
            if (specialCaseHW && (ch == 'H' || ch == 'W')) {
                continue;
            }
            char digit = map(ch);
            if (digit == SILENT_MARKER) {
                continue;
            }
            if (digit != '0' && digit != lastDigit) {
                out[count++] = digit;
            }
            lastDigit = digit;
        }
        return new String(out);
    }
}
