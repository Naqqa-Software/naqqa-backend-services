package com.naqqa.elasticsearch.analysis.stem.snowball;

import com.naqqa.elasticsearch.analysis.stem.Stemmer;

public final class FinnishSnowballStemmer implements Stemmer {

    private static final String AEI = "aäei";
    private static final String C = "bcdfghjklmnpqrstvwxz";
    private static final String V1 = "aeiouyäö";
    private static final String V2 = "aeiouäö";
    private static final String PARTICLE_END = "aeiouyäönt";

    private static final String[] PARTICLE = {
        "kin", "kaan", "kään", "ko", "kö", "han", "hän", "pa", "pä", "sti"
    };

    private static final String[] POSSESSIVE = {
        "si", "ni", "nsa", "nsä", "mme", "nne", "an", "än", "en"
    };

    private static final String[] AN_CONTEXT = {"ta", "ssa", "sta", "lla", "lta", "na"};
    private static final String[] AN_UMLAUT_CONTEXT = {"tä", "ssä", "stä", "llä", "ltä", "nä"};
    private static final String[] EN_CONTEXT = {"lle", "ine"};

    private static final String[] LONG = {"aa", "ee", "ii", "oo", "uu", "ää", "öö"};

    private static final String[] CASE = {
        "han", "hen", "hin", "hon", "hän", "hön", "siin", "seen", "den", "tten", "n",
        "a", "ä", "tta", "ttä", "ta", "tä", "ssa", "ssä", "sta", "stä",
        "lla", "llä", "lta", "ltä", "lle", "na", "nä", "ksi", "ine"
    };

    private static final String[] OTHER = {
        "mpi", "mpa", "mpä", "mmi", "mma", "mmä",
        "impi", "impa", "impä", "immi", "imma", "immä", "eja", "ejä"
    };

    public FinnishSnowballStemmer() {
    }

    @Override
    public boolean stem(StringBuilder w) {
        int original = w.length();
        int len = original;
        int p1 = len;
        int p2 = len;
        int i = skipTo(w, 0, true);
        if (i < len) {
            i = skipTo(w, i, false);
            if (i < len) {
                p1 = i + 1;
                i = skipTo(w, p1, true);
                if (i < len) {
                    i = skipTo(w, i, false);
                    if (i < len) {
                        p2 = i + 1;
                    }
                }
            }
        }
        particle(w, p1, p2);
        possessive(w, p1);
        boolean endingRemoved = caseEnding(w, p1);
        otherEndings(w, p2);
        if (endingRemoved) {
            int end = w.length();
            if (end - 1 >= p1) {
                char c = w.charAt(end - 1);
                if (c == 'i' || c == 'j') {
                    w.setLength(end - 1);
                }
            }
        } else {
            tPlural(w, p1, p2);
        }
        tidy(w, p1);
        return w.length() != original;
    }

    private static int skipTo(StringBuilder w, int from, boolean vowel) {
        int len = w.length();
        int i = from;
        while (i < len && (V1.indexOf(w.charAt(i)) >= 0) != vowel) {
            i++;
        }
        return i;
    }

    private static boolean isIn(String group, StringBuilder w, int index, int limit) {
        return index >= limit && index >= 0 && group.indexOf(w.charAt(index)) >= 0;
    }

    private static void particle(StringBuilder w, int p1, int p2) {
        int end = w.length();
        int m = NordicSupport.longest(w, end, p1, PARTICLE);
        if (m < 0) {
            return;
        }
        int start = end - PARTICLE[m].length();
        if (m == 9) {
            if (start < p2) {
                return;
            }
        } else if (!isIn(PARTICLE_END, w, start - 1, 0)) {
            return;
        }
        w.setLength(start);
    }

    private static void possessive(StringBuilder w, int p1) {
        int end = w.length();
        int m = NordicSupport.longest(w, end, p1, POSSESSIVE);
        if (m < 0) {
            return;
        }
        int start = end - POSSESSIVE[m].length();
        switch (m) {
            case 0 -> {
                if (start >= 1 && w.charAt(start - 1) == 'k') {
                    return;
                }
                w.setLength(start);
            }
            case 1 -> {
                w.setLength(start);
                if (NordicSupport.endsWith(w, start, 0, "kse")) {
                    w.setCharAt(start - 1, 'i');
                }
            }
            case 2, 3, 4, 5 -> w.setLength(start);
            case 6 -> {
                if (NordicSupport.longest(w, start, 0, AN_CONTEXT) >= 0) {
                    w.setLength(start);
                }
            }
            case 7 -> {
                if (NordicSupport.longest(w, start, 0, AN_UMLAUT_CONTEXT) >= 0) {
                    w.setLength(start);
                }
            }
            default -> {
                if (NordicSupport.longest(w, start, 0, EN_CONTEXT) >= 0) {
                    w.setLength(start);
                }
            }
        }
    }

    private static boolean vi(StringBuilder w, int pos, int limit) {
        return pos - 2 >= limit && w.charAt(pos - 1) == 'i' && V2.indexOf(w.charAt(pos - 2)) >= 0;
    }

    private static boolean isLong(StringBuilder w, int pos, int limit) {
        return NordicSupport.longest(w, pos, limit, LONG) >= 0;
    }

    private static boolean caseEnding(StringBuilder w, int p1) {
        int end = w.length();
        int best = -1;
        int bestLen = -1;
        for (int k = 0; k < CASE.length; k++) {
            String s = CASE[k];
            int n = s.length();
            if (n <= bestLen || !NordicSupport.endsWith(w, end, p1, s)) {
                continue;
            }
            int pos = end - n;
            boolean ok = switch (s) {
                case "siin", "den", "tten" -> vi(w, pos, p1);
                case "seen" -> isLong(w, pos, p1);
                default -> true;
            };
            if (ok) {
                best = k;
                bestLen = n;
            }
        }
        if (best < 0) {
            return false;
        }
        String s = CASE[best];
        int start = end - s.length();
        switch (s) {
            case "han", "hen", "hin", "hon", "hän", "hön" -> {
                if (start < 1 || w.charAt(start - 1) != s.charAt(1)) {
                    return false;
                }
            }
            case "tta", "ttä" -> {
                if (start < 1 || w.charAt(start - 1) != 'e') {
                    return false;
                }
            }
            case "n" -> {
                if ((isLong(w, start, 0) || NordicSupport.endsWith(w, start, 0, "ie")) && start >= 1) {
                    start--;
                }
            }
            case "a", "ä" -> {
                if (!isIn(V1, w, start - 1, 0) || !isIn(C, w, start - 2, 0)) {
                    return false;
                }
            }
            default -> {
            }
        }
        w.setLength(start);
        return true;
    }

    private static void otherEndings(StringBuilder w, int p2) {
        int end = w.length();
        int m = NordicSupport.longest(w, end, p2, OTHER);
        if (m < 0) {
            return;
        }
        int start = end - OTHER[m].length();
        if (m < 6 && NordicSupport.endsWith(w, start, 0, "po")) {
            return;
        }
        w.setLength(start);
    }

    private static void tPlural(StringBuilder w, int p1, int p2) {
        int end = w.length();
        if (end - 2 < p1 || w.charAt(end - 1) != 't' || V1.indexOf(w.charAt(end - 2)) < 0) {
            return;
        }
        end--;
        w.setLength(end);
        if (NordicSupport.endsWith(w, end, p2, "imma")) {
            w.setLength(end - 4);
        } else if (NordicSupport.endsWith(w, end, p2, "mma") && !NordicSupport.endsWith(w, end - 3, 0, "po")) {
            w.setLength(end - 3);
        }
    }

    private static void tidy(StringBuilder w, int p1) {
        int end = w.length();
        if (end < p1) {
            return;
        }
        if (isLong(w, end, p1)) {
            end--;
            w.setLength(end);
        }
        if (isIn(AEI, w, end - 1, p1) && isIn(C, w, end - 2, p1)) {
            end--;
            w.setLength(end);
        }
        if (end - 2 >= p1 && w.charAt(end - 1) == 'j' && (w.charAt(end - 2) == 'o' || w.charAt(end - 2) == 'u')) {
            end--;
            w.setLength(end);
        }
        if (end - 2 >= p1 && w.charAt(end - 1) == 'o' && w.charAt(end - 2) == 'j') {
            end--;
            w.setLength(end);
        }
        int i = end;
        while (i > 0 && V1.indexOf(w.charAt(i - 1)) >= 0) {
            i--;
        }
        if (i < 2) {
            return;
        }
        char x = w.charAt(i - 1);
        if (C.indexOf(x) >= 0 && w.charAt(i - 2) == x) {
            w.deleteCharAt(i - 1);
        }
    }
}
