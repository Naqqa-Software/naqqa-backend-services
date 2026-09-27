package com.naqqa.elasticsearch.analysis.charfilter.html;

import com.naqqa.elasticsearch.analysis.CharFilter;
import com.naqqa.elasticsearch.analysis.FilteredText;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class HtmlStripCharFilter extends CharFilter {

    private final Set<String> escapedTags;
    private final boolean escapeBr;
    private final boolean escapeScript;
    private final boolean escapeStyle;

    public HtmlStripCharFilter() {
        this(null);
    }

    public HtmlStripCharFilter(Set<String> escapedTags) {
        Set<String> tags = new HashSet<>();
        boolean br = false;
        boolean script = false;
        boolean style = false;
        if (escapedTags != null) {
            for (String tag : escapedTags) {
                if (tag == null) {
                    continue;
                }
                if (tag.equalsIgnoreCase("BR")) {
                    br = true;
                } else if (tag.equalsIgnoreCase("SCRIPT")) {
                    script = true;
                } else if (tag.equalsIgnoreCase("STYLE")) {
                    style = true;
                } else {
                    tags.add(tag.toLowerCase(Locale.ROOT));
                }
            }
        }
        this.escapedTags = Collections.unmodifiableSet(tags);
        this.escapeBr = br;
        this.escapeScript = script;
        this.escapeStyle = style;
    }

    @Override
    public void filter(CharSequence input, FilteredText output) {
        new HtmlStripScanner(input, output, escapedTags, escapeBr, escapeScript, escapeStyle).run();
    }
}
