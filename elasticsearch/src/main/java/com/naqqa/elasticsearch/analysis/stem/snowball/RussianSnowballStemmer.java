package com.naqqa.elasticsearch.analysis.stem.snowball;

import com.naqqa.elasticsearch.analysis.stem.Stemmer;

public final class RussianSnowballStemmer implements Stemmer {

    private static final String VOWELS = "аеиоуыэюя";

    private static final String[] PERFECTIVE_GERUND = {
        "в", "вши", "вшись",
        "ив", "ивши", "ившись",
        "ыв", "ывши", "ывшись"
    };
    private static final int[] PERFECTIVE_GERUND_GROUP = {1, 1, 1, 2, 2, 2, 2, 2, 2};

    private static final String[] ADJECTIVE = {
        "ее", "ие", "ые", "ое", "ими", "ыми",
        "ей", "ий", "ый", "ой", "ем", "им",
        "ым", "ом", "его", "ого", "ему",
        "ому", "их", "ых", "ую", "юю", "ая",
        "яя", "ою", "ею"
    };

    private static final String[] PARTICIPLE = {
        "ем", "нн", "вш", "ющ", "щ",
        "ивш", "ывш", "ующ"
    };
    private static final int[] PARTICIPLE_GROUP = {1, 1, 1, 1, 1, 2, 2, 2};

    private static final String[] REFLEXIVE = {"ся", "сь"};

    private static final String[] VERB = {
        "ла", "на", "ете", "йте", "ли", "й",
        "л", "ем", "н", "ло", "но", "ет", "ют",
        "ны", "ть", "ешь", "нно",
        "ила", "ыла", "ена", "ейте",
        "уйте", "ите", "или", "ыли", "ей",
        "уй", "ил", "ыл", "им", "ым", "ен",
        "ило", "ыло", "ено", "ят", "ует",
        "уют", "ит", "ыт", "ены", "ить",
        "ыть", "ишь", "ую", "ю"
    };
    private static final int VERB_GROUP_ONE_COUNT = 17;

    private static final String[] NOUN = {
        "а", "ев", "ов", "ие", "ье", "е",
        "иями", "ями", "ами", "еи", "ии",
        "и", "ией", "ей", "ой", "ий", "й",
        "иям", "ям", "ием", "ем", "ам", "ом",
        "о", "у", "ах", "иях", "ях", "ы", "ь",
        "ию", "ью", "ю", "ия", "ья", "я"
    };

    private static final String[] DERIVATIONAL = {"ост", "ость"};

    private static final String[] TIDY_UP = {"ейш", "ейше", "н", "ь"};

    public RussianSnowballStemmer() {
    }

    @Override
    public boolean stem(StringBuilder w) {
        int original = w.length();
        boolean changed = false;
        for (int i = 0; i < original; i++) {
            if (w.charAt(i) == 'ё') {
                w.setCharAt(i, 'е');
                changed = true;
            }
        }
        int len = w.length();
        int pV = len;
        int p2 = len;
        int i = 0;
        while (i < len && !isVowel(w.charAt(i))) {
            i++;
        }
        if (i < len) {
            i++;
            pV = i;
            while (i < len && isVowel(w.charAt(i))) {
                i++;
            }
            if (i < len) {
                i++;
                while (i < len && !isVowel(w.charAt(i))) {
                    i++;
                }
                if (i < len) {
                    i++;
                    while (i < len && isVowel(w.charAt(i))) {
                        i++;
                    }
                    if (i < len) {
                        p2 = i + 1;
                    }
                }
            }
        }
        if (!perfectiveGerund(w, pV)) {
            int end = w.length();
            int m = NordicSupport.longest(w, end, pV, REFLEXIVE);
            if (m >= 0) {
                w.setLength(end - REFLEXIVE[m].length());
            }
            if (!adjectival(w, pV) && !verb(w, pV)) {
                end = w.length();
                m = NordicSupport.longest(w, end, pV, NOUN);
                if (m >= 0) {
                    w.setLength(end - NOUN[m].length());
                }
            }
        }
        int end = w.length();
        if (end - 1 >= pV && w.charAt(end - 1) == 'и') {
            w.setLength(end - 1);
            end--;
        }
        int m = NordicSupport.longest(w, end, pV, DERIVATIONAL);
        if (m >= 0 && end - DERIVATIONAL[m].length() >= p2) {
            w.setLength(end - DERIVATIONAL[m].length());
        }
        tidyUp(w, pV);
        return changed || w.length() != original;
    }

    private static boolean isVowel(char c) {
        return VOWELS.indexOf(c) >= 0;
    }

    private static boolean precededByAOrYa(StringBuilder w, int start, int pV) {
        if (start - 1 < pV) {
            return false;
        }
        char c = w.charAt(start - 1);
        return c == 'а' || c == 'я';
    }

    private static boolean perfectiveGerund(StringBuilder w, int pV) {
        int end = w.length();
        int m = NordicSupport.longest(w, end, pV, PERFECTIVE_GERUND);
        if (m < 0) {
            return false;
        }
        int start = end - PERFECTIVE_GERUND[m].length();
        if (PERFECTIVE_GERUND_GROUP[m] == 1 && !precededByAOrYa(w, start, pV)) {
            return false;
        }
        w.setLength(start);
        return true;
    }

    private static boolean adjectival(StringBuilder w, int pV) {
        int end = w.length();
        int m = NordicSupport.longest(w, end, pV, ADJECTIVE);
        if (m < 0) {
            return false;
        }
        end -= ADJECTIVE[m].length();
        w.setLength(end);
        m = NordicSupport.longest(w, end, pV, PARTICIPLE);
        if (m >= 0) {
            int start = end - PARTICIPLE[m].length();
            if (PARTICIPLE_GROUP[m] == 2 || precededByAOrYa(w, start, pV)) {
                w.setLength(start);
            }
        }
        return true;
    }

    private static boolean verb(StringBuilder w, int pV) {
        int end = w.length();
        int m = NordicSupport.longest(w, end, pV, VERB);
        if (m < 0) {
            return false;
        }
        int start = end - VERB[m].length();
        if (m < VERB_GROUP_ONE_COUNT && !precededByAOrYa(w, start, pV)) {
            return false;
        }
        w.setLength(start);
        return true;
    }

    private static void tidyUp(StringBuilder w, int pV) {
        int end = w.length();
        int m = NordicSupport.longest(w, end, pV, TIDY_UP);
        if (m < 0) {
            return;
        }
        if (m <= 1) {
            end -= TIDY_UP[m].length();
            w.setLength(end);
            if (end - 2 >= pV && w.charAt(end - 1) == 'н' && w.charAt(end - 2) == 'н') {
                w.setLength(end - 1);
            }
        } else if (m == 2) {
            if (end - 2 >= pV && w.charAt(end - 2) == 'н') {
                w.setLength(end - 1);
            }
        } else {
            w.setLength(end - 1);
        }
    }
}
