package com.naqqa.elasticsearch.analysis.lang.a;

import com.naqqa.elasticsearch.analysis.stem.snowball.SnowballSupport;

public final class IrishSnowballStemmer extends SnowballSupport {

    private static final String V = "aeiouáéíóú";

    private static final Among INITIAL = Among.of(
        g("b'", "d'", "h-", "m'", "n-", "t-"),
        g("bhf", "d'fh", "fh"),
        g("sh", "ts"),
        g("bh", "mb"),
        g("ch", "gc"),
        g("dh", "nd"),
        g("gh", "ng"),
        g("bp", "ph"),
        g("dt", "th"),
        g("mh"));

    private static final Among NOUN_SFX = Among.of(
        g("íochta", "aíochta", "abh", "eabh", "ibh", "aibh", "amh", "eamh", "imh", "aimh", "íocht", "aíocht"),
        g("ire", "aire", "irí", "airí"));

    private static final Among DERIV = Among.of(
        g("achta", "eachta", "ach", "each", "achtúil", "eachtúil", "acht", "eacht"),
        g("arcachta", "arcacht", "arcachtaí"),
        g("gineach", "gineas", "ginis"),
        g("grafaíochta", "grafaíoch", "grafaíocht", "grafaíochtaí"),
        g("patacha", "paite", "patach", "pataigh"),
        g("óideacha", "óideach", "óidigh"));

    private static final Among VERB_SFX = Among.of(
        g("faidh", "fidh", "imid", "aimid", "ímid", "aímid"),
        g("adh", "eadh", "áil", "ain", "tear", "tar"));

    private int p2;
    private int p1;
    private int pV;

    public IrishSnowballStemmer() {
    }

    @Override
    protected void run() {
        int c = cursor;
        initialMorph();
        cursor = c;
        markRegions();
        limitBackward = cursor;
        cursor = limit;
        int v2 = limit - cursor;
        nounSfx();
        cursor = limit - v2;
        int v3 = limit - cursor;
        deriv();
        cursor = limit - v3;
        int v4 = limit - cursor;
        verbSfx();
        cursor = limit - v4;
        cursor = limitBackward;
    }

    private void markRegions() {
        pV = limit;
        p1 = limit;
        p2 = limit;
        int c = cursor;
        if (goPastIn(V)) {
            pV = cursor;
            if (goPastOut(V)) {
                p1 = cursor;
                if (goPastIn(V) && goPastOut(V)) {
                    p2 = cursor;
                }
            }
        }
        cursor = c;
    }

    private boolean rv() {
        return pV <= cursor;
    }

    private boolean r1() {
        return p1 <= cursor;
    }

    private boolean r2() {
        return p2 <= cursor;
    }

    private boolean initialMorph() {
        bra = cursor;
        int a = findAmong(INITIAL);
        if (a == 0) {
            return false;
        }
        ket = cursor;
        switch (a) {
            case 1 -> sliceDel();
            case 2 -> sliceFrom("f");
            case 3 -> sliceFrom("s");
            case 4 -> sliceFrom("b");
            case 5 -> sliceFrom("c");
            case 6 -> sliceFrom("d");
            case 7 -> sliceFrom("g");
            case 8 -> sliceFrom("p");
            case 9 -> sliceFrom("t");
            case 10 -> sliceFrom("m");
            default -> {
            }
        }
        return true;
    }

    private boolean nounSfx() {
        ket = cursor;
        int a = findAmongB(NOUN_SFX);
        if (a == 0) {
            return false;
        }
        bra = cursor;
        switch (a) {
            case 1 -> {
                if (!r1()) {
                    return false;
                }
                sliceDel();
            }
            case 2 -> {
                if (!r2()) {
                    return false;
                }
                sliceDel();
            }
            default -> {
            }
        }
        return true;
    }

    private boolean deriv() {
        ket = cursor;
        int a = findAmongB(DERIV);
        if (a == 0) {
            return false;
        }
        bra = cursor;
        switch (a) {
            case 1 -> {
                if (!r2()) {
                    return false;
                }
                sliceDel();
            }
            case 2 -> sliceFrom("arc");
            case 3 -> sliceFrom("gin");
            case 4 -> sliceFrom("graf");
            case 5 -> sliceFrom("paite");
            case 6 -> sliceFrom("óid");
            default -> {
            }
        }
        return true;
    }

    private boolean verbSfx() {
        ket = cursor;
        int a = findAmongB(VERB_SFX);
        if (a == 0) {
            return false;
        }
        bra = cursor;
        switch (a) {
            case 1 -> {
                if (!rv()) {
                    return false;
                }
                sliceDel();
            }
            case 2 -> {
                if (!r1()) {
                    return false;
                }
                sliceDel();
            }
            default -> {
            }
        }
        return true;
    }
}
