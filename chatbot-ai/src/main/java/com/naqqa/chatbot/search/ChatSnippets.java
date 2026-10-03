package com.naqqa.chatbot.search;

import com.naqqa.chatbot.ai.TextNormalizer;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ChatSnippets {

    private ChatSnippets() {
    }

    static String foldSameLength(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isSurrogate(c)) {
                sb.append(c);
                continue;
            }
            String d = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
            char base = d.isEmpty() ? c : d.charAt(0);
            char lower = String.valueOf(base).toLowerCase(Locale.ROOT).charAt(0);
            sb.append(lower == 'ё' ? 'е' : lower);
        }
        return sb.toString();
    }

    public static List<MessageSearchHit.Range> ranges(String text, String query, Collection<String> variants) {
        List<MessageSearchHit.Range> out = new ArrayList<>();
        if (text == null || text.isEmpty() || query == null) {
            return out;
        }
        Set<String> terms = new LinkedHashSet<>();
        for (String t : TextNormalizer.tokens(query)) {
            if (t.length() >= 2 || Character.isDigit(t.charAt(0))) {
                terms.add(t);
            }
        }
        if (variants != null) {
            for (String v : variants) {
                for (String t : TextNormalizer.tokens(v)) {
                    if (t.length() >= 2) {
                        terms.add(t);
                    }
                }
            }
        }
        String folded = foldSameLength(text);
        for (String term : terms) {
            int from = 0;
            while (from < folded.length()) {
                int at = folded.indexOf(term, from);
                if (at < 0) {
                    break;
                }
                boolean wordStart = at == 0 || !Character.isLetterOrDigit(folded.charAt(at - 1));
                if (wordStart) {
                    int end = at + term.length();
                    while (end < folded.length() && Character.isLetterOrDigit(folded.charAt(end))) {
                        end++;
                    }
                    out.add(new MessageSearchHit.Range(at, end));
                }
                from = at + term.length();
            }
        }
        out.sort(Comparator.comparingInt(MessageSearchHit.Range::start));
        List<MessageSearchHit.Range> merged = new ArrayList<>();
        for (MessageSearchHit.Range r : out) {
            if (!merged.isEmpty() && r.start() <= merged.get(merged.size() - 1).end()) {
                MessageSearchHit.Range last = merged.remove(merged.size() - 1);
                merged.add(new MessageSearchHit.Range(last.start(), Math.max(last.end(), r.end())));
            } else {
                merged.add(r);
            }
        }
        return merged;
    }
}
