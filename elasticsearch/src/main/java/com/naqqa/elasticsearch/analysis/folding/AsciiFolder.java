package com.naqqa.elasticsearch.analysis.folding;

public final class AsciiFolder {

    private static final char[][] TABLE = buildTable();

    private AsciiFolder() {
    }

    private static char[][] buildTable() {
        char[][] table = new char[65536][];
        String data = AsciiFoldingData.DATA;
        int i = 0;
        int n = data.length();
        while (i < n) {
            char key = data.charAt(i++);
            int start = i;
            while (i < n && data.charAt(i) < 0x80) {
                i++;
            }
            table[key] = data.substring(start, i).toCharArray();
        }
        return table;
    }

    public static int foldToASCII(char[] input, int inputPos, char[] output, int outputPos, int length) {
        int end = inputPos + length;
        for (int pos = inputPos; pos < end; ++pos) {
            char c = input[pos];
            if (c < 0x80) {
                output[outputPos++] = c;
                continue;
            }
            char[] mapped = TABLE[c];
            if (mapped == null) {
                output[outputPos++] = c;
            } else {
                for (char m : mapped) {
                    output[outputPos++] = m;
                }
            }
        }
        return outputPos;
    }

    public static boolean needsFolding(char[] input, int off, int len) {
        int end = off + len;
        for (int i = off; i < end; i++) {
            if (input[i] >= 0x80) {
                return true;
            }
        }
        return false;
    }

    public static String fold(String s) {
        if (s == null) {
            return null;
        }
        char[] in = s.toCharArray();
        if (!needsFolding(in, 0, in.length)) {
            return s;
        }
        char[] out = new char[in.length * 4];
        int len = foldToASCII(in, 0, out, 0, in.length);
        return new String(out, 0, len);
    }

    public static String foldingOf(char c) {
        if (c < 0x80) {
            return null;
        }
        char[] mapped = TABLE[c];
        return mapped == null ? null : new String(mapped);
    }
}
