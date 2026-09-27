package com.naqqa.elasticsearch.search.highlight;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class HighlightRequest {

    private final String field;
    private final Set<String> terms = new LinkedHashSet<>();
    private final List<Pattern> phrases = new java.util.ArrayList<>();
    private String[] preTags = {"<em>"};
    private String[] postTags = {"</em>"};
    private int numberOfFragments = 5;
    private int fragmentSize = 100;
    private BoundaryScanner boundaryScanner = BoundaryScanner.WORD;
    private boolean requireFieldMatch = true;
    private int noMatchSize = 0;

    public HighlightRequest(String field) {
        this.field = field;
    }

    public String field() {
        return field;
    }

    public HighlightRequest addTerm(String term) {
        this.terms.add(term.toLowerCase(java.util.Locale.ROOT));
        return this;
    }

    public HighlightRequest terms(Iterable<String> terms) {
        for (String t : terms) {
            addTerm(t);
        }
        return this;
    }

    public Set<String> terms() {
        return terms;
    }

    public HighlightRequest addPhrase(String literalPhrase) {
        this.phrases.add(Pattern.compile(Pattern.quote(literalPhrase), Pattern.CASE_INSENSITIVE));
        return this;
    }

    public HighlightRequest addPattern(Pattern pattern) {
        this.phrases.add(pattern);
        return this;
    }

    public List<Pattern> phrases() {
        return phrases;
    }

    public HighlightRequest preTags(String... tags) {
        this.preTags = tags;
        return this;
    }

    public HighlightRequest postTags(String... tags) {
        this.postTags = tags;
        return this;
    }

    public String preTag() {
        return preTags.length > 0 ? preTags[0] : "<em>";
    }

    public String postTag() {
        return postTags.length > 0 ? postTags[0] : "</em>";
    }

    public HighlightRequest numberOfFragments(int n) {
        this.numberOfFragments = n;
        return this;
    }

    public int numberOfFragments() {
        return numberOfFragments;
    }

    public HighlightRequest fragmentSize(int size) {
        this.fragmentSize = size;
        return this;
    }

    public int fragmentSize() {
        return fragmentSize;
    }

    public HighlightRequest boundaryScanner(BoundaryScanner scanner) {
        this.boundaryScanner = scanner;
        return this;
    }

    public BoundaryScanner boundaryScanner() {
        return boundaryScanner;
    }

    public HighlightRequest requireFieldMatch(boolean value) {
        this.requireFieldMatch = value;
        return this;
    }

    public boolean requireFieldMatch() {
        return requireFieldMatch;
    }

    public HighlightRequest noMatchSize(int size) {
        this.noMatchSize = size;
        return this;
    }

    public int noMatchSize() {
        return noMatchSize;
    }
}
