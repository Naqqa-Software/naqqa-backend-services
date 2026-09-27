package com.naqqa.elasticsearch.analysis;

public abstract class Tokenizer extends TokenStream {

    protected CharSequence input = "";
    private OffsetCorrector corrector = OffsetCorrector.IDENTITY;

    protected Tokenizer() {
    }

    public final void setInput(CharSequence input) {
        setInput(input, OffsetCorrector.IDENTITY);
    }

    public final void setInput(CharSequence input, OffsetCorrector corrector) {
        this.input = input == null ? "" : input;
        this.corrector = corrector == null ? OffsetCorrector.IDENTITY : corrector;
    }

    protected final int correctOffset(int offset) {
        return corrector.correctOffset(offset);
    }

    @Override
    public void reset() {
    }

    @Override
    public void end() {
        token.clear();
        token.setPositionIncrement(0);
        int finalOffset = correctOffset(input.length());
        token.setOffset(finalOffset, finalOffset);
    }

    @Override
    public void close() {
        input = "";
        corrector = OffsetCorrector.IDENTITY;
    }
}
