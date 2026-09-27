package com.naqqa.elasticsearch.analysis.filter.worddelimiter;

import com.naqqa.elasticsearch.analysis.Token;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static com.naqqa.elasticsearch.analysis.filter.worddelimiter.WordDelimiterGraphFilter.*;

final class SlowWdf {

    private static final int NUMBER = 0;
    private static final int LETTER = 1;
    private static final int DELIM = 2;

    private SlowWdf() {
    }

    private record WordPart(String part, int type) {
    }

    private static int toType(char ch) {
        if (Character.isDigit(ch)) {
            return NUMBER;
        } else if (Character.isLetter(ch)) {
            return LETTER;
        }
        return DELIM;
    }

    private static boolean has(int flags, int flag) {
        return (flags & flag) != 0;
    }

    static String randomText(Random random) {
        StringBuilder b = new StringBuilder();
        int length = 1 + random.nextInt(20);
        for (int i = 0; i < length; i++) {
            int r = random.nextInt(37);
            if (r < 10) {
                b.append((char) ('a' + random.nextInt(26)));
            } else if (r < 20) {
                b.append((char) ('A' + random.nextInt(26)));
            } else if (r < 30) {
                b.append((char) ('0' + random.nextInt(10)));
            } else if (r < 35) {
                b.append('-');
            } else {
                b.append("'s");
            }
        }
        return b.toString();
    }

    static void verify(String text, int flags) {
        Set<String> expected = slowWdf(text, flags);
        Set<String> actual = WdAssert.graphStrings(new WordDelimiterGraphFilter(
            new TestCannedTokenStream(new Token(text, 0, text.length())), flags, null));
        if (!expected.equals(actual)) {
            throw new AssertionError("text=" + text + " flags=" + flagsToString(flags) + " expected=" + new TreeSet<>(expected)
                + " actual=" + new TreeSet<>(actual));
        }
    }

    static Set<String> slowWdf(String text, int flags) {
        List<WordPart> wordParts = new ArrayList<>();
        int lastCH = -1;
        int wordPartStart = 0;
        boolean inToken = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (toType(ch) == DELIM) {
                if (inToken) {
                    wordParts.add(part(text, wordPartStart, i));
                    inToken = false;
                }
                if (has(flags, STEM_ENGLISH_POSSESSIVE)
                    && ch == '\''
                    && i > 0
                    && i < text.length() - 1
                    && (text.charAt(i + 1) == 's' || text.charAt(i + 1) == 'S')
                    && toType(text.charAt(i - 1)) == LETTER
                    && (i + 2 == text.length() || toType(text.charAt(i + 2)) == DELIM)) {
                    i += 2;
                }
            } else if (!inToken) {
                inToken = true;
                wordPartStart = i;
            } else {
                boolean newToken = false;
                if (Character.isLetter(lastCH)) {
                    if (Character.isLetter(ch)) {
                        if (has(flags, SPLIT_ON_CASE_CHANGE) && Character.isLowerCase(lastCH) && !Character.isLowerCase(ch)) {
                            newToken = true;
                        }
                    } else if (has(flags, SPLIT_ON_NUMERICS) && Character.isDigit(ch)) {
                        newToken = true;
                    }
                } else {
                    if (Character.isLetter(ch) && has(flags, SPLIT_ON_NUMERICS)) {
                        newToken = true;
                    }
                }
                if (newToken) {
                    wordParts.add(part(text, wordPartStart, i));
                    wordPartStart = i;
                }
            }
            lastCH = ch;
        }
        if (inToken) {
            wordParts.add(part(text, wordPartStart, text.length()));
        }
        Set<String> paths = new HashSet<>();
        if (!wordParts.isEmpty()) {
            enumerate(flags, 0, wordParts, paths, new StringBuilder());
        }
        if (has(flags, PRESERVE_ORIGINAL)) {
            paths.add(text);
        }
        if (has(flags, CATENATE_ALL) && !wordParts.isEmpty()) {
            StringBuilder b = new StringBuilder();
            for (WordPart wordPart : wordParts) {
                b.append(wordPart.part());
            }
            paths.add(b.toString());
        }
        return paths;
    }

    private static WordPart part(String text, int start, int end) {
        String p = text.substring(start, end);
        return new WordPart(p, toType(p.charAt(0)));
    }

    private static void add(StringBuilder path, String part) {
        if (path.length() != 0) {
            path.append(' ');
        }
        path.append(part);
    }

    private static void add(StringBuilder path, List<WordPart> wordParts, int from, int to) {
        if (path.length() != 0) {
            path.append(' ');
        }
        for (int i = from; i < to; i++) {
            path.append(wordParts.get(i).part());
        }
    }

    private static void addWithSpaces(StringBuilder path, List<WordPart> wordParts, int from, int to) {
        for (int i = from; i < to; i++) {
            add(path, wordParts.get(i).part());
        }
    }

    private static int endOfRun(List<WordPart> wordParts, int start) {
        int upto = start + 1;
        while (upto < wordParts.size() && wordParts.get(upto).type() == wordParts.get(start).type()) {
            upto++;
        }
        return upto;
    }

    private static void enumerate(int flags, int upto, List<WordPart> wordParts, Set<String> paths, StringBuilder path) {
        if (upto == wordParts.size()) {
            if (path.length() > 0) {
                paths.add(path.toString());
            }
            return;
        }
        int savLength = path.length();
        int end = endOfRun(wordParts, upto);
        boolean number = wordParts.get(upto).type() == NUMBER;
        int generate = number ? GENERATE_NUMBER_PARTS : GENERATE_WORD_PARTS;
        int catenate = number ? CATENATE_NUMBERS : CATENATE_WORDS;
        if (has(flags, generate) || wordParts.size() == 1) {
            addWithSpaces(path, wordParts, upto, end);
            if (has(flags, catenate)) {
                enumerate(flags, end, wordParts, paths, path);
                path.setLength(savLength);
                add(path, wordParts, upto, end);
            }
        } else if (has(flags, catenate)) {
            add(path, wordParts, upto, end);
        }
        enumerate(flags, end, wordParts, paths, path);
        path.setLength(savLength);
    }
}
