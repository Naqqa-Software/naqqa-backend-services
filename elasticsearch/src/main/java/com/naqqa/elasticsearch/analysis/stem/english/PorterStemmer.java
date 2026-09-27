package com.naqqa.elasticsearch.analysis.stem.english;

import com.naqqa.elasticsearch.analysis.stem.Stemmer;

public final class PorterStemmer implements Stemmer {

    private char[] b = new char[64];
    private int j;
    private int k;

    public PorterStemmer() {
    }

    @Override
    public boolean stem(StringBuilder word) {
        int len = word.length();
        if (b.length < len) {
            b = new char[Math.max(len, b.length * 2)];
        }
        word.getChars(0, len, b, 0);
        k = len - 1;
        if (k > 1) {
            step1();
            step2();
            step3();
            step4();
            step5();
            step6();
        }
        int newLen = k + 1;
        boolean changed = newLen != len;
        for (int i = 0; !changed && i < len; i++) {
            changed = b[i] != word.charAt(i);
        }
        if (changed) {
            word.setLength(0);
            word.append(b, 0, newLen);
        }
        return changed;
    }

    private boolean cons(int i) {
        switch (b[i]) {
            case 'a':
            case 'e':
            case 'i':
            case 'o':
            case 'u':
                return false;
            case 'y':
                return i == 0 || !cons(i - 1);
            default:
                return true;
        }
    }

    private int m() {
        int n = 0;
        int i = 0;
        while (true) {
            if (i > j) {
                return n;
            }
            if (!cons(i)) {
                break;
            }
            i++;
        }
        i++;
        while (true) {
            while (true) {
                if (i > j) {
                    return n;
                }
                if (cons(i)) {
                    break;
                }
                i++;
            }
            i++;
            n++;
            while (true) {
                if (i > j) {
                    return n;
                }
                if (!cons(i)) {
                    break;
                }
                i++;
            }
            i++;
        }
    }

    private boolean vowelInStem() {
        for (int i = 0; i <= j; i++) {
            if (!cons(i)) {
                return true;
            }
        }
        return false;
    }

    private boolean doublec(int i) {
        if (i < 1) {
            return false;
        }
        if (b[i] != b[i - 1]) {
            return false;
        }
        return cons(i);
    }

    private boolean cvc(int i) {
        if (i < 2 || !cons(i) || cons(i - 1) || !cons(i - 2)) {
            return false;
        }
        char ch = b[i];
        return ch != 'w' && ch != 'x' && ch != 'y';
    }

    private boolean ends(String s) {
        int l = s.length();
        int o = k - l + 1;
        if (o < 0) {
            return false;
        }
        for (int i = 0; i < l; i++) {
            if (b[o + i] != s.charAt(i)) {
                return false;
            }
        }
        j = k - l;
        return true;
    }

    private void setto(String s) {
        int l = s.length();
        int o = j + 1;
        if (o + l > b.length) {
            b = java.util.Arrays.copyOf(b, (o + l) * 2);
        }
        for (int i = 0; i < l; i++) {
            b[o + i] = s.charAt(i);
        }
        k = j + l;
    }

    private void r(String s) {
        if (m() > 0) {
            setto(s);
        }
    }

    private void step1() {
        if (b[k] == 's') {
            if (ends("sses")) {
                k -= 2;
            } else if (ends("ies")) {
                setto("i");
            } else if (b[k - 1] != 's') {
                k--;
            }
        }
        if (ends("eed")) {
            if (m() > 0) {
                k--;
            }
        } else if ((ends("ed") || ends("ing")) && vowelInStem()) {
            k = j;
            if (ends("at")) {
                setto("ate");
            } else if (ends("bl")) {
                setto("ble");
            } else if (ends("iz")) {
                setto("ize");
            } else if (doublec(k)) {
                char ch = b[k--];
                if (ch == 'l' || ch == 's' || ch == 'z') {
                    k++;
                }
            } else if (m() == 1 && cvc(k)) {
                setto("e");
            }
        }
    }

    private void step2() {
        if (ends("y") && vowelInStem()) {
            b[k] = 'i';
        }
    }

    private void step3() {
        if (k == 0) {
            return;
        }
        switch (b[k - 1]) {
            case 'a':
                if (ends("ational")) {
                    r("ate");
                } else if (ends("tional")) {
                    r("tion");
                }
                break;
            case 'c':
                if (ends("enci")) {
                    r("ence");
                } else if (ends("anci")) {
                    r("ance");
                }
                break;
            case 'e':
                if (ends("izer")) {
                    r("ize");
                }
                break;
            case 'l':
                if (ends("bli")) {
                    r("ble");
                } else if (ends("alli")) {
                    r("al");
                } else if (ends("entli")) {
                    r("ent");
                } else if (ends("eli")) {
                    r("e");
                } else if (ends("ousli")) {
                    r("ous");
                }
                break;
            case 'o':
                if (ends("ization")) {
                    r("ize");
                } else if (ends("ation")) {
                    r("ate");
                } else if (ends("ator")) {
                    r("ate");
                }
                break;
            case 's':
                if (ends("alism")) {
                    r("al");
                } else if (ends("iveness")) {
                    r("ive");
                } else if (ends("fulness")) {
                    r("ful");
                } else if (ends("ousness")) {
                    r("ous");
                }
                break;
            case 't':
                if (ends("aliti")) {
                    r("al");
                } else if (ends("iviti")) {
                    r("ive");
                } else if (ends("biliti")) {
                    r("ble");
                }
                break;
            case 'g':
                if (ends("logi")) {
                    r("log");
                }
                break;
            default:
                break;
        }
    }

    private void step4() {
        switch (b[k]) {
            case 'e':
                if (ends("icate")) {
                    r("ic");
                } else if (ends("ative")) {
                    r("");
                } else if (ends("alize")) {
                    r("al");
                }
                break;
            case 'i':
                if (ends("iciti")) {
                    r("ic");
                }
                break;
            case 'l':
                if (ends("ical")) {
                    r("ic");
                } else if (ends("ful")) {
                    r("");
                }
                break;
            case 's':
                if (ends("ness")) {
                    r("");
                }
                break;
            default:
                break;
        }
    }

    private void step5() {
        if (k == 0) {
            return;
        }
        switch (b[k - 1]) {
            case 'a':
                if (ends("al")) {
                    break;
                }
                return;
            case 'c':
                if (ends("ance") || ends("ence")) {
                    break;
                }
                return;
            case 'e':
                if (ends("er")) {
                    break;
                }
                return;
            case 'i':
                if (ends("ic")) {
                    break;
                }
                return;
            case 'l':
                if (ends("able") || ends("ible")) {
                    break;
                }
                return;
            case 'n':
                if (ends("ant") || ends("ement") || ends("ment") || ends("ent")) {
                    break;
                }
                return;
            case 'o':
                if (ends("ion") && j >= 0 && (b[j] == 's' || b[j] == 't')) {
                    break;
                }
                if (ends("ou")) {
                    break;
                }
                return;
            case 's':
                if (ends("ism")) {
                    break;
                }
                return;
            case 't':
                if (ends("ate") || ends("iti")) {
                    break;
                }
                return;
            case 'u':
                if (ends("ous")) {
                    break;
                }
                return;
            case 'v':
                if (ends("ive")) {
                    break;
                }
                return;
            case 'z':
                if (ends("ize")) {
                    break;
                }
                return;
            default:
                return;
        }
        if (m() > 1) {
            k = j;
        }
    }

    private void step6() {
        j = k;
        if (b[k] == 'e') {
            int a = m();
            if (a > 1 || a == 1 && !cvc(k - 1)) {
                k--;
            }
        }
        if (b[k] == 'l' && doublec(k) && m() > 1) {
            k--;
        }
    }
}
