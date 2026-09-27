package com.naqqa.elasticsearch.analysis.tokenizer;

public class LetterTokenizer extends WhitespaceTokenizer {

    public LetterTokenizer() {
        super(255);
    }

    @Override
    protected boolean isTokenChar(int cp) {
        return Character.isLetter(cp);
    }
}
