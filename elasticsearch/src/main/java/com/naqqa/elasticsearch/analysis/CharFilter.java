package com.naqqa.elasticsearch.analysis;

public abstract class CharFilter {

    public abstract void filter(CharSequence input, FilteredText output);

    public String apply(CharSequence input) {
        FilteredText out = new FilteredText();
        out.reset(OffsetCorrector.IDENTITY);
        filter(input, out);
        return out.text().toString();
    }
}
