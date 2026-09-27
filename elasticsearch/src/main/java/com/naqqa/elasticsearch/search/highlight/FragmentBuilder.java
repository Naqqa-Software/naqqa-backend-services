package com.naqqa.elasticsearch.search.highlight;

import java.util.ArrayList;
import java.util.List;

final class FragmentBuilder {

    private FragmentBuilder() {
    }

    private static final class Fragment {
        final int start;
        final int end;
        final List<Match> matches;
        final int order;
        int score;

        Fragment(int start, int end, List<Match> matches, int order) {
            this.start = start;
            this.end = end;
            this.matches = matches;
            this.order = order;
            for (Match m : matches) {
                score += m.weight();
            }
        }
    }

    static List<String> buildFragments(String text, List<Match> matches, HighlightRequest request) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        if (matches.isEmpty()) {
            return noMatchFallback(text, request);
        }
        List<Fragment> candidates = windowFragments(text, matches, request);
        candidates.sort((a, b) -> {
            if (a.score != b.score) {
                return Integer.compare(b.score, a.score);
            }
            return Integer.compare(a.order, b.order);
        });
        int limit = Math.min(request.numberOfFragments(), candidates.size());
        List<Fragment> selected = new ArrayList<>(candidates.subList(0, limit));
        selected.sort((a, b) -> Integer.compare(a.order, b.order));
        List<String> out = new ArrayList<>();
        for (Fragment f : selected) {
            out.add(applyTags(renderFragment(text, f), request));
        }
        return out;
    }

    private static List<Fragment> windowFragments(String text, List<Match> matches, HighlightRequest request) {
        List<Fragment> fragments = new ArrayList<>();
        int i = 0;
        int order = 0;
        int fragmentSize = Math.max(1, request.fragmentSize());
        BoundaryScanner scanner = request.boundaryScanner();
        while (i < matches.size()) {
            Match first = matches.get(i);
            int idealEnd = first.start() + fragmentSize;
            int j = i;
            int coveredEnd = first.end();
            while (j + 1 < matches.size() && matches.get(j + 1).end() <= idealEnd) {
                j++;
                coveredEnd = Math.max(coveredEnd, matches.get(j).end());
            }
            int rawStart = first.start();
            int rawEnd = Math.max(coveredEnd, Math.min(text.length(), rawStart + fragmentSize));
            int fragStart = scanner.precedingBoundary(text, rawStart);
            int fragEnd = scanner.followingBoundary(text, rawEnd);
            if (fragEnd <= fragStart) {
                fragEnd = Math.min(text.length(), fragStart + 1);
            }
            fragments.add(new Fragment(fragStart, fragEnd, new ArrayList<>(matches.subList(i, j + 1)), order++));
            i = j + 1;
        }
        return fragments;
    }

    private static List<String> noMatchFallback(String text, HighlightRequest request) {
        if (request.noMatchSize() <= 0) {
            return List.of();
        }
        int end = request.boundaryScanner().followingBoundary(text, Math.min(text.length(), request.noMatchSize()));
        return List.of(text.substring(0, end));
    }

    private static String renderFragment(String text, Fragment fragment) {
        List<int[]> merged = mergeSpans(fragment.matches, fragment.start, fragment.end);
        StringBuilder sb = new StringBuilder();
        int cursor = fragment.start;
        for (int[] span : merged) {
            int s = Math.max(span[0], fragment.start);
            int e = Math.min(span[1], fragment.end);
            if (s > cursor) {
                sb.append(text, cursor, s);
            }
            sb.append("\u0000PRE\u0000");
            sb.append(text, s, e);
            sb.append("\u0000POST\u0000");
            cursor = e;
        }
        if (cursor < fragment.end) {
            sb.append(text, cursor, fragment.end);
        }
        return sb.toString();
    }

    private static List<int[]> mergeSpans(List<Match> matches, int fragStart, int fragEnd) {
        List<int[]> spans = new ArrayList<>();
        for (Match m : matches) {
            int s = Math.max(m.start(), fragStart);
            int e = Math.min(m.end(), fragEnd);
            if (e > s) {
                spans.add(new int[]{s, e});
            }
        }
        spans.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
        List<int[]> merged = new ArrayList<>();
        for (int[] span : spans) {
            if (!merged.isEmpty() && span[0] <= merged.get(merged.size() - 1)[1]) {
                int[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], span[1]);
            } else {
                merged.add(span);
            }
        }
        return merged;
    }

    private static String applyTags(String rendered, HighlightRequest request) {
        return rendered.replace("\u0000PRE\u0000", request.preTag()).replace("\u0000POST\u0000", request.postTag());
    }
}
