package com.naqqa.elasticsearch.analysis.stem.english;

import com.naqqa.elasticsearch.analysis.stem.Stemmer;

public final class KStemmer implements Stemmer {

    private static final int MAX_WORD_LEN = 50;

    private static final char[] IZATION = "ization".toCharArray();
    private static final char[] ITION = "ition".toCharArray();
    private static final char[] ATION = "ation".toCharArray();
    private static final char[] ICATION = "ication".toCharArray();

    private static final Dictionary DICT = Dictionary.build();

    private char[] word = new char[MAX_WORD_LEN + 16];
    private int length;
    private char[] term = new char[MAX_WORD_LEN + 16];
    private int j;
    private int k;
    private int matchedEntry = -1;

    public KStemmer() {
    }

    @Override
    public boolean stem(StringBuilder input) {
        int len = input.length();
        int last = len - 1;
        if (last <= 1 || last >= MAX_WORD_LEN - 1) {
            return false;
        }
        if (term.length < len) {
            term = new char[len];
        }
        input.getChars(0, len, term, 0);
        int entry = DICT.get(term, 0, len);
        if (entry >= 0) {
            String root = DICT.roots[entry];
            if (root != null) {
                return replace(input, root);
            }
            return false;
        }
        length = 0;
        if (word.length < len + 10) {
            word = new char[len + 10];
        }
        for (int i = 0; i < len; i++) {
            char ch = term[i];
            if (ch < 'a' || ch > 'z') {
                return false;
            }
            word[length++] = ch;
        }
        k = last;
        matchedEntry = -1;
        run();
        entry = matchedEntry;
        if (entry >= 0 && DICT.roots[entry] != null) {
            return replace(input, DICT.roots[entry]);
        }
        boolean changed = length != len;
        for (int i = 0; !changed && i < len; i++) {
            changed = word[i] != term[i];
        }
        if (changed) {
            input.setLength(0);
            input.append(word, 0, length);
        }
        return changed;
    }

    private static boolean replace(StringBuilder input, String value) {
        if (input.length() == value.length()) {
            boolean same = true;
            for (int i = 0; i < value.length() && same; i++) {
                same = input.charAt(i) == value.charAt(i);
            }
            if (same) {
                return false;
            }
        }
        input.setLength(0);
        input.append(value);
        return true;
    }

    private void run() {
        plural();
        if (matched()) {
            return;
        }
        pastTense();
        if (matched()) {
            return;
        }
        aspect();
        if (matched()) {
            return;
        }
        ityEndings();
        if (matched()) {
            return;
        }
        nessEndings();
        if (matched()) {
            return;
        }
        ionEndings();
        if (matched()) {
            return;
        }
        erAndOrEndings();
        if (matched()) {
            return;
        }
        lyEndings();
        if (matched()) {
            return;
        }
        alEndings();
        if (matched()) {
            return;
        }
        wordInDict();
        iveEndings();
        if (matched()) {
            return;
        }
        izeEndings();
        if (matched()) {
            return;
        }
        mentEndings();
        if (matched()) {
            return;
        }
        bleEndings();
        if (matched()) {
            return;
        }
        ismEndings();
        if (matched()) {
            return;
        }
        icEndings();
        if (matched()) {
            return;
        }
        ncyEndings();
        if (matched()) {
            return;
        }
        nceEndings();
    }

    private boolean matched() {
        return matchedEntry >= 0;
    }

    private char charAt(int index) {
        return word[index];
    }

    private void setCharAt(int index, char ch) {
        word[index] = ch;
    }

    private void setLength(int len) {
        length = len;
    }

    private void write(char ch) {
        if (length == word.length) {
            word = java.util.Arrays.copyOf(word, word.length * 2);
        }
        word[length++] = ch;
    }

    private void append(String s) {
        for (int i = 0; i < s.length(); i++) {
            write(s.charAt(i));
        }
    }

    private char penultChar() {
        return word[k - 1];
    }

    private boolean isVowel(int index) {
        return !isCons(index);
    }

    private boolean isCons(int index) {
        char ch = word[index];
        if (ch == 'a' || ch == 'e' || ch == 'i' || ch == 'o' || ch == 'u') {
            return false;
        }
        if (ch != 'y' || index == 0) {
            return true;
        }
        return !isCons(index - 1);
    }

    private int stemLength() {
        return j + 1;
    }

    private boolean endsIn(char[] s) {
        if (s.length > k) {
            return false;
        }
        int r = length - s.length;
        j = k;
        for (int r1 = r, i = 0; i < s.length; i++, r1++) {
            if (s[i] != word[r1]) {
                return false;
            }
        }
        j = r - 1;
        return true;
    }

    private boolean endsIn(char a, char b) {
        if (2 > k) {
            return false;
        }
        if (word[k - 1] == a && word[k] == b) {
            j = k - 2;
            return true;
        }
        return false;
    }

    private boolean endsIn(char a, char b, char c) {
        if (3 > k) {
            return false;
        }
        if (word[k - 2] == a && word[k - 1] == b && word[k] == c) {
            j = k - 3;
            return true;
        }
        return false;
    }

    private boolean endsIn(char a, char b, char c, char d) {
        if (4 > k) {
            return false;
        }
        if (word[k - 3] == a && word[k - 2] == b && word[k - 1] == c && word[k] == d) {
            j = k - 4;
            return true;
        }
        return false;
    }

    private int wordInDict() {
        if (matchedEntry >= 0) {
            return matchedEntry;
        }
        int e = DICT.get(word, 0, length);
        if (e >= 0 && !DICT.exceptions[e]) {
            matchedEntry = e;
        }
        return e;
    }

    private boolean lookup() {
        matchedEntry = DICT.get(word, 0, length);
        return matchedEntry >= 0;
    }

    private void setSuffix(String s) {
        setLength(j + 1);
        append(s);
        k = j + s.length();
    }

    private void plural() {
        if (word[k] == 's') {
            if (endsIn('i', 'e', 's')) {
                setLength(j + 3);
                k--;
                if (lookup()) {
                    return;
                }
                k++;
                write('s');
                setSuffix("y");
                lookup();
            } else if (endsIn('e', 's')) {
                setLength(j + 2);
                k--;
                boolean tryE = j > 0 && !(word[j] == 's' && word[j - 1] == 's');
                if (tryE && lookup()) {
                    return;
                }
                setLength(j + 1);
                k--;
                if (lookup()) {
                    return;
                }
                write('e');
                k++;
                if (!tryE) {
                    lookup();
                }
            } else {
                if (length > 3 && penultChar() != 's' && !endsIn('o', 'u', 's')) {
                    setLength(k);
                    k--;
                    lookup();
                }
            }
        }
    }

    private void pastTense() {
        if (length <= 4) {
            return;
        }
        if (endsIn('i', 'e', 'd')) {
            setLength(j + 3);
            k--;
            if (lookup()) {
                return;
            }
            k++;
            write('d');
            setSuffix("y");
            lookup();
            return;
        }
        if (endsIn('e', 'd') && vowelInStem()) {
            setLength(j + 2);
            k = j + 1;
            int entry = wordInDict();
            if (entry >= 0 && !DICT.exceptions[entry]) {
                return;
            }
            setLength(j + 1);
            k = j;
            if (lookup()) {
                return;
            }
            if (doubleC(k)) {
                setLength(k);
                k--;
                if (lookup()) {
                    return;
                }
                write(word[k]);
                k++;
                lookup();
                return;
            }
            if (word[0] == 'u' && word[1] == 'n') {
                write('e');
                write('d');
                k = k + 2;
                return;
            }
            setLength(j + 1);
            write('e');
            k = j + 1;
        }
    }

    private boolean doubleC(int i) {
        if (i < 1) {
            return false;
        }
        if (word[i] != word[i - 1]) {
            return false;
        }
        return isCons(i);
    }

    private boolean vowelInStem() {
        for (int i = 0; i < stemLength(); i++) {
            if (isVowel(i)) {
                return true;
            }
        }
        return false;
    }

    private void aspect() {
        if (length <= 5) {
            return;
        }
        if (endsIn('i', 'n', 'g') && vowelInStem()) {
            setCharAt(j + 1, 'e');
            setLength(j + 2);
            k = j + 1;
            int entry = wordInDict();
            if (entry >= 0 && !DICT.exceptions[entry]) {
                return;
            }
            setLength(k);
            k--;
            if (lookup()) {
                return;
            }
            if (doubleC(k)) {
                k--;
                setLength(k + 1);
                if (lookup()) {
                    return;
                }
                write(word[k]);
                k++;
                lookup();
                return;
            }
            if (j > 0 && isCons(j) && isCons(j - 1)) {
                k = j;
                setLength(k + 1);
                return;
            }
            setLength(j + 1);
            write('e');
            k = j + 1;
        }
    }

    private void ityEndings() {
        int oldK = k;
        if (endsIn('i', 't', 'y')) {
            setLength(j + 1);
            k = j;
            if (lookup()) {
                return;
            }
            write('e');
            k = j + 1;
            if (lookup()) {
                return;
            }
            setCharAt(j + 1, 'i');
            append("ty");
            k = oldK;
            if (j > 0 && word[j - 1] == 'i' && word[j] == 'l') {
                setLength(j - 1);
                append("le");
                k = j;
                lookup();
                return;
            }
            if (j > 0 && word[j - 1] == 'i' && word[j] == 'v') {
                setLength(j + 1);
                write('e');
                k = j + 1;
                lookup();
                return;
            }
            if (j > 0 && word[j - 1] == 'a' && word[j] == 'l') {
                setLength(j + 1);
                k = j;
                lookup();
                return;
            }
            if (lookup()) {
                return;
            }
            setLength(j + 1);
            k = j;
        }
    }

    private void nceEndings() {
        int oldK = k;
        if (endsIn('n', 'c', 'e')) {
            char wordChar = word[j];
            if (!(wordChar == 'e' || wordChar == 'a')) {
                return;
            }
            setLength(j);
            write('e');
            k = j;
            if (lookup()) {
                return;
            }
            setLength(j);
            k = j - 1;
            if (lookup()) {
                return;
            }
            write(wordChar);
            append("nce");
            k = oldK;
        }
    }

    private void nessEndings() {
        if (endsIn('n', 'e', 's', 's')) {
            setLength(j + 1);
            k = j;
            if (word[j] == 'i') {
                setCharAt(j, 'y');
            }
            lookup();
        }
    }

    private void ismEndings() {
        if (endsIn('i', 's', 'm')) {
            setLength(j + 1);
            k = j;
            lookup();
        }
    }

    private void mentEndings() {
        int oldK = k;
        if (endsIn('m', 'e', 'n', 't')) {
            setLength(j + 1);
            k = j;
            if (lookup()) {
                return;
            }
            append("ment");
            k = oldK;
        }
    }

    private void izeEndings() {
        int oldK = k;
        if (endsIn('i', 'z', 'e')) {
            setLength(j + 1);
            k = j;
            if (lookup()) {
                return;
            }
            write('i');
            if (doubleC(j)) {
                setLength(j);
                k = j - 1;
                if (lookup()) {
                    return;
                }
                write(word[j - 1]);
            }
            setLength(j + 1);
            write('e');
            k = j + 1;
            if (lookup()) {
                return;
            }
            setLength(j + 1);
            append("ize");
            k = oldK;
        }
    }

    private void ncyEndings() {
        if (endsIn('n', 'c', 'y')) {
            if (!(word[j] == 'e' || word[j] == 'a')) {
                return;
            }
            setCharAt(j + 2, 't');
            setLength(j + 3);
            k = j + 2;
            if (lookup()) {
                return;
            }
            setCharAt(j + 2, 'c');
            write('e');
            k = j + 3;
            lookup();
        }
    }

    private void bleEndings() {
        int oldK = k;
        if (endsIn('b', 'l', 'e')) {
            if (!(word[j] == 'a' || word[j] == 'i')) {
                return;
            }
            char wordChar = word[j];
            setLength(j);
            k = j - 1;
            if (lookup()) {
                return;
            }
            if (doubleC(k)) {
                setLength(k);
                k--;
                if (lookup()) {
                    return;
                }
                k++;
                write(word[k - 1]);
            }
            setLength(j);
            write('e');
            k = j;
            if (lookup()) {
                return;
            }
            setLength(j);
            append("ate");
            k = j + 2;
            if (lookup()) {
                return;
            }
            setLength(j);
            write(wordChar);
            append("ble");
            k = oldK;
        }
    }

    private void icEndings() {
        if (endsIn('i', 'c')) {
            setLength(j + 3);
            append("al");
            k = j + 4;
            if (lookup()) {
                return;
            }
            setCharAt(j + 1, 'y');
            setLength(j + 2);
            k = j + 1;
            if (lookup()) {
                return;
            }
            setCharAt(j + 1, 'e');
            if (lookup()) {
                return;
            }
            setLength(j + 1);
            k = j;
            if (lookup()) {
                return;
            }
            append("ic");
            k = j + 2;
        }
    }

    private void ionEndings() {
        int oldK = k;
        if (!endsIn('i', 'o', 'n')) {
            return;
        }
        if (endsIn(IZATION)) {
            setLength(j + 3);
            write('e');
            k = j + 3;
            lookup();
            return;
        }
        if (endsIn(ITION)) {
            setLength(j + 1);
            write('e');
            k = j + 1;
            if (lookup()) {
                return;
            }
            setLength(j + 1);
            append("ition");
            k = oldK;
        } else if (endsIn(ATION)) {
            setLength(j + 3);
            write('e');
            k = j + 3;
            if (lookup()) {
                return;
            }
            setLength(j + 1);
            write('e');
            k = j + 1;
            if (lookup()) {
                return;
            }
            setLength(j + 1);
            k = j;
            if (lookup()) {
                return;
            }
            setLength(j + 1);
            append("ation");
            k = oldK;
        }
        if (endsIn(ICATION)) {
            setLength(j + 1);
            write('y');
            k = j + 1;
            if (lookup()) {
                return;
            }
            setLength(j + 1);
            append("ication");
            k = oldK;
        }
        j = k - 3;
        setLength(j + 1);
        write('e');
        k = j + 1;
        if (lookup()) {
            return;
        }
        setLength(j + 1);
        k = j;
        if (lookup()) {
            return;
        }
        setLength(j + 1);
        append("ion");
        k = oldK;
    }

    private void erAndOrEndings() {
        int oldK = k;
        if (word[k] != 'r') {
            return;
        }
        if (endsIn('i', 'z', 'e', 'r')) {
            setLength(j + 4);
            k = j + 3;
            lookup();
            return;
        }
        if (endsIn('e', 'r') || endsIn('o', 'r')) {
            char wordChar = word[j + 1];
            if (doubleC(j)) {
                setLength(j);
                k = j - 1;
                if (lookup()) {
                    return;
                }
                write(word[j - 1]);
            }
            if (word[j] == 'i') {
                setCharAt(j, 'y');
                setLength(j + 1);
                k = j;
                if (lookup()) {
                    return;
                }
                setCharAt(j, 'i');
                write('e');
            }
            if (word[j] == 'e') {
                setLength(j);
                k = j - 1;
                if (lookup()) {
                    return;
                }
                write('e');
            }
            setLength(j + 2);
            k = j + 1;
            if (lookup()) {
                return;
            }
            setLength(j + 1);
            k = j;
            if (lookup()) {
                return;
            }
            write('e');
            k = j + 1;
            if (lookup()) {
                return;
            }
            setLength(j + 1);
            write(wordChar);
            write('r');
            k = oldK;
        }
    }

    private void lyEndings() {
        int oldK = k;
        if (endsIn('l', 'y')) {
            setCharAt(j + 2, 'e');
            if (lookup()) {
                return;
            }
            setCharAt(j + 2, 'y');
            setLength(j + 1);
            k = j;
            if (lookup()) {
                return;
            }
            if (j > 0 && word[j - 1] == 'a' && word[j] == 'l') {
                return;
            }
            append("ly");
            k = oldK;
            if (j > 0 && word[j - 1] == 'a' && word[j] == 'b') {
                setCharAt(j + 2, 'e');
                k = j + 2;
                return;
            }
            if (word[j] == 'i') {
                setLength(j);
                write('y');
                k = j;
                if (lookup()) {
                    return;
                }
                setLength(j);
                append("ily");
                k = oldK;
            }
            setLength(j + 1);
            k = j;
        }
    }

    private void alEndings() {
        int oldK = k;
        if (length < 4) {
            return;
        }
        if (endsIn('a', 'l')) {
            setLength(j + 1);
            k = j;
            if (lookup()) {
                return;
            }
            if (doubleC(j)) {
                setLength(j);
                k = j - 1;
                if (lookup()) {
                    return;
                }
                write(word[j - 1]);
            }
            setLength(j + 1);
            write('e');
            k = j + 1;
            if (lookup()) {
                return;
            }
            setLength(j + 1);
            append("um");
            k = j + 2;
            if (lookup()) {
                return;
            }
            setLength(j + 1);
            append("al");
            k = oldK;
            if (j > 0 && word[j - 1] == 'i' && word[j] == 'c') {
                setLength(j - 1);
                k = j - 2;
                if (lookup()) {
                    return;
                }
                setLength(j - 1);
                write('y');
                k = j - 1;
                if (lookup()) {
                    return;
                }
                setLength(j - 1);
                append("ic");
                k = j;
                lookup();
                return;
            }
            if (word[j] == 'i') {
                setLength(j);
                k = j - 1;
                if (lookup()) {
                    return;
                }
                append("ial");
                k = oldK;
                lookup();
            }
        }
    }

    private void iveEndings() {
        int oldK = k;
        if (endsIn('i', 'v', 'e')) {
            setLength(j + 1);
            k = j;
            if (lookup()) {
                return;
            }
            write('e');
            k = j + 1;
            if (lookup()) {
                return;
            }
            setLength(j + 1);
            append("ive");
            if (j > 0 && word[j - 1] == 'a' && word[j] == 't') {
                setCharAt(j - 1, 'e');
                setLength(j);
                k = j - 1;
                if (lookup()) {
                    return;
                }
                setLength(j - 1);
                if (lookup()) {
                    return;
                }
                append("ative");
                k = oldK;
            }
            setCharAt(j + 2, 'o');
            setCharAt(j + 3, 'n');
            if (lookup()) {
                return;
            }
            setCharAt(j + 2, 'v');
            setCharAt(j + 3, 'e');
            k = oldK;
        }
    }

    private static final class Dictionary {

        private final char[][] keys;
        private final int[] slots;
        private final String[] roots;
        private final boolean[] exceptions;
        private final int mask;
        private int size;

        private Dictionary(int capacity) {
            int tableSize = Integer.highestOneBit(capacity * 2 - 1) << 1;
            this.slots = new int[tableSize];
            java.util.Arrays.fill(slots, -1);
            this.mask = tableSize - 1;
            this.keys = new char[capacity][];
            this.roots = new String[capacity];
            this.exceptions = new boolean[capacity];
        }

        static Dictionary build() {
            String[] headWords = KStemData.headWords();
            int capacity = KStemData.EXCEPTION_WORDS.length + KStemData.DIRECT_CONFLATIONS.length
                + KStemData.COUNTRY_NATIONALITY.length + headWords.length
                + KStemData.SUPPLEMENT_DICT.length + KStemData.PROPER_NOUNS.length;
            Dictionary d = new Dictionary(capacity);
            for (String w : KStemData.EXCEPTION_WORDS) {
                d.put(w, w, true);
            }
            for (String[] pair : KStemData.DIRECT_CONFLATIONS) {
                d.put(pair[0], pair[1], false);
            }
            for (String[] pair : KStemData.COUNTRY_NATIONALITY) {
                d.put(pair[0], pair[1], false);
            }
            for (String w : headWords) {
                d.put(w, null, false);
            }
            for (String w : KStemData.SUPPLEMENT_DICT) {
                d.put(w, null, false);
            }
            for (String w : KStemData.PROPER_NOUNS) {
                d.put(w, null, false);
            }
            return d;
        }

        private static int hash(char[] text, int offset, int len) {
            int h = 0;
            for (int i = offset; i < offset + len; i++) {
                h = 31 * h + text[i];
            }
            return h ^ (h >>> 16);
        }

        private void put(String key, String root, boolean exception) {
            char[] chars = key.toCharArray();
            if (get(chars, 0, chars.length) >= 0) {
                throw new IllegalStateException("duplicate dictionary entry " + key);
            }
            int slot = hash(chars, 0, chars.length) & mask;
            while (slots[slot] >= 0) {
                slot = (slot + 1) & mask;
            }
            keys[size] = chars;
            roots[size] = root;
            exceptions[size] = exception;
            slots[slot] = size++;
        }

        int get(char[] text, int offset, int len) {
            int slot = hash(text, offset, len) & mask;
            while (true) {
                int index = slots[slot];
                if (index < 0) {
                    return -1;
                }
                char[] key = keys[index];
                if (key.length == len) {
                    boolean eq = true;
                    for (int i = 0; i < len; i++) {
                        if (key[i] != text[offset + i]) {
                            eq = false;
                            break;
                        }
                    }
                    if (eq) {
                        return index;
                    }
                }
                slot = (slot + 1) & mask;
            }
        }
    }
}
