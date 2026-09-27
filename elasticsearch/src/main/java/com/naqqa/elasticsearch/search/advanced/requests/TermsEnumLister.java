package com.naqqa.elasticsearch.search.advanced.requests;

import com.naqqa.elasticsearch.codec.terms.SeekStatus;
import com.naqqa.elasticsearch.codec.terms.TermsEnum;
import com.naqqa.elasticsearch.search.execution.LeafReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class TermsEnumLister {

    private TermsEnumLister() {
    }

    public record TermCount(String term, int docFreq) {
    }

    public static List<TermCount> list(LeafReader reader, String field, String prefix, int size) throws IOException {
        List<TermCount> results = new ArrayList<>();
        TermsEnum te = reader.terms(field);
        if (te == null) {
            return results;
        }
        byte[] prefixBytes = prefix == null ? new byte[0] : prefix.getBytes(StandardCharsets.UTF_8);
        SeekStatus status = te.seekCeil(prefixBytes);
        if (status == SeekStatus.END) {
            return results;
        }
        byte[] term = te.term();
        while (term != null && startsWith(term, prefixBytes) && results.size() < size) {
            results.add(new TermCount(new String(term, StandardCharsets.UTF_8), te.docFreq()));
            term = te.next();
        }
        return results;
    }

    private static boolean startsWith(byte[] term, byte[] prefix) {
        if (term.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (term[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
