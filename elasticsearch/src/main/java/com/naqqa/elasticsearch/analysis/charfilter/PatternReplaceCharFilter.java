package com.naqqa.elasticsearch.analysis.charfilter;

import com.naqqa.elasticsearch.analysis.CharFilter;
import com.naqqa.elasticsearch.analysis.FilteredText;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PatternReplaceCharFilter extends CharFilter {

    private final Pattern pattern;
    private final String replacement;

    public PatternReplaceCharFilter(Pattern pattern, String replacement) {
        this.pattern = pattern;
        this.replacement = replacement == null ? "" : replacement;
    }

    @Override
    public void filter(CharSequence input, FilteredText output) {
        String text = input.toString();
        Matcher matcher = pattern.matcher(text);
        StringBuilder out = output.text();
        int last = 0;
        int cumulativeDiff = 0;
        while (matcher.find()) {
            out.append(text, last, matcher.start());
            int before = out.length();
            appendReplacement(matcher, out, text);
            int matchedLen = matcher.end() - matcher.start();
            int appended = out.length() - before;
            cumulativeDiff += matchedLen - appended;
            output.addOffCorrectMap(out.length(), cumulativeDiff);
            last = matcher.end();
            if (matcher.start() == matcher.end()) {
                if (last >= text.length()) {
                    break;
                }
                out.append(text.charAt(last));
                last++;
            }
        }
        out.append(text, last, text.length());
    }

    private void appendReplacement(Matcher matcher, StringBuilder out, String text) {
        int n = replacement.length();
        int i = 0;
        while (i < n) {
            char c = replacement.charAt(i);
            if (c == '\\' && i + 1 < n) {
                out.append(replacement.charAt(i + 1));
                i += 2;
            } else if (c == '$' && i + 1 < n && Character.isDigit(replacement.charAt(i + 1))) {
                int j = i + 1;
                while (j < n && Character.isDigit(replacement.charAt(j))) {
                    j++;
                }
                int group = Integer.parseInt(replacement.substring(i + 1, j));
                String g = group <= matcher.groupCount() ? matcher.group(group) : null;
                if (g != null) {
                    out.append(g);
                }
                i = j;
            } else {
                out.append(c);
                i++;
            }
        }
    }
}
