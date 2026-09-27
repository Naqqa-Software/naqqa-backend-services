package com.naqqa.elasticsearch.analysis.phonetic;

public class RefinedSoundex implements PhoneticEncoder {

    public static final String US_ENGLISH_MAPPING_STRING = "01360240043788015936020505";

    private final char[] soundexMapping;

    public RefinedSoundex() {
        this.soundexMapping = US_ENGLISH_MAPPING_STRING.toCharArray();
    }

    public RefinedSoundex(String mapping) {
        this.soundexMapping = mapping.toCharArray();
    }

    @Override
    public String encode(String input) {
        if (input == null) {
            return null;
        }
        String str = SoundexUtils.clean(input);
        if (str.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(str.charAt(0));
        char last = '*';
        for (int i = 0; i < str.length(); i++) {
            char current = getMappingCode(str.charAt(i));
            if (current == last) {
                continue;
            }
            if (current != 0) {
                sb.append(current);
            }
            last = current;
        }
        return sb.toString();
    }

    public int difference(String s1, String s2) {
        return SoundexUtils.differenceEncoded(encode(s1), encode(s2));
    }

    char getMappingCode(char c) {
        if (!Character.isLetter(c)) {
            return 0;
        }
        int index = Character.toUpperCase(c) - 'A';
        if (index < 0 || index >= soundexMapping.length) {
            return 0;
        }
        return soundexMapping[index];
    }
}
