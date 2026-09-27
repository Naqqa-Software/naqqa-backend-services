package com.naqqa.elasticsearch.codec.docvalues;

import com.naqqa.elasticsearch.store.DataInput;
import com.naqqa.elasticsearch.store.DataOutput;

import java.io.IOException;
import java.util.Arrays;

public final class SimpleTermDictionary {

    private SimpleTermDictionary() {
    }

    public static void write(DataOutput out, byte[][] sortedUniqueTerms) throws IOException {
        out.writeVInt(sortedUniqueTerms.length);
        byte[] prev = new byte[0];
        for (byte[] term : sortedUniqueTerms) {
            int prefixLen = commonPrefixLength(prev, term);
            int suffixLen = term.length - prefixLen;
            out.writeVInt(prefixLen);
            out.writeVInt(suffixLen);
            out.writeBytes(term, prefixLen, suffixLen);
            prev = term;
        }
    }

    public static byte[][] read(DataInput in) throws IOException {
        int count = in.readVInt();
        byte[][] terms = new byte[count][];
        byte[] prev = new byte[0];
        for (int i = 0; i < count; i++) {
            int prefixLen = in.readVInt();
            int suffixLen = in.readVInt();
            byte[] term = new byte[prefixLen + suffixLen];
            System.arraycopy(prev, 0, term, 0, prefixLen);
            in.readBytes(term, prefixLen, suffixLen);
            terms[i] = term;
            prev = term;
        }
        return terms;
    }

    private static int commonPrefixLength(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        int i = 0;
        while (i < n && a[i] == b[i]) {
            i++;
        }
        return i;
    }

    public static int compare(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int ai = a[i] & 0xFF;
            int bi = b[i] & 0xFF;
            if (ai != bi) {
                return ai - bi;
            }
        }
        return a.length - b.length;
    }

    public static int lookupOrd(byte[][] sortedTerms, byte[] term) {
        return Arrays.binarySearch(sortedTerms, term, SimpleTermDictionary::compare);
    }
}
