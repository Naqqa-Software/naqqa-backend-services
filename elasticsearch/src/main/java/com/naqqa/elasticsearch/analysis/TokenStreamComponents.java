package com.naqqa.elasticsearch.analysis;

public final class TokenStreamComponents {

    private static final CharFilter[] NO_CHAR_FILTERS = new CharFilter[0];

    private final CharFilter[] charFilters;
    private final FilteredText[] buffers;
    private final Tokenizer source;
    private final TokenStream sink;

    public TokenStreamComponents(Tokenizer source) {
        this(NO_CHAR_FILTERS, source, source);
    }

    public TokenStreamComponents(Tokenizer source, TokenStream sink) {
        this(NO_CHAR_FILTERS, source, sink);
    }

    public TokenStreamComponents(CharFilter[] charFilters, Tokenizer source, TokenStream sink) {
        this.charFilters = charFilters == null ? NO_CHAR_FILTERS : charFilters;
        this.buffers = new FilteredText[this.charFilters.length];
        for (int i = 0; i < buffers.length; i++) {
            buffers[i] = new FilteredText();
        }
        this.source = source;
        this.sink = sink;
    }

    public Tokenizer source() {
        return source;
    }

    public TokenStream sink() {
        return sink;
    }

    public CharFilter[] charFilters() {
        return charFilters;
    }

    public void setInput(CharSequence text) {
        CharSequence current = text == null ? "" : text;
        OffsetCorrector corrector = OffsetCorrector.IDENTITY;
        for (int i = 0; i < charFilters.length; i++) {
            FilteredText buffer = buffers[i];
            buffer.reset(corrector);
            charFilters[i].filter(current, buffer);
            current = buffer.text();
            corrector = buffer;
        }
        source.setInput(current, corrector);
    }
}
