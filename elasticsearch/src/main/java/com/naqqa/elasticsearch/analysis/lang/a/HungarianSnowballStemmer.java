package com.naqqa.elasticsearch.analysis.lang.a;

import com.naqqa.elasticsearch.analysis.stem.snowball.SnowballSupport;

public final class HungarianSnowballStemmer extends SnowballSupport {

    private static final String V = "aeiouáéíóöőúüű";

    private static final Among V_ENDING = Among.of(g("á"), g("é"));

    private static final Among DOUBLE = Among.of(g(
        "bb", "cc", "dd", "ff", "gg", "jj", "kk", "ll", "mm", "nn", "pp", "rr", "ccs", "ss", "zzs", "tt", "vv", "ggy", "lly", "nny", "tty", "ssz", "zz"));

    private static final Among INSTRUM = Among.of(g("al", "el"));

    private static final Among CASE_ = Among.of(g(
        "ba", "ra", "be", "re", "ig", "nak", "nek", "val", "vel", "ul", "nál", "nél", "ból",
        "ról", "tól", "ül", "ből", "ről", "től", "n", "an", "ban", "en", "ben",
        "képpen", "on", "ön", "képp", "kor", "t", "at", "et", "ként", "anként",
        "enként", "onként", "ot", "ért", "öt", "hez", "hoz", "höz", "vá",
        "vé"));

    private static final Among CASE_SPECIAL = Among.of(g("én"), g("án", "ánként"));

    private static final Among CASE_OTHER = Among.of(
        g("stul", "astul", "stül", "estül"),
        g("ástul"),
        g("éstül"));

    private static final Among FACTIVE = Among.of(g("á", "é"));

    private static final Among PLURAL = Among.of(
        g("ák"),
        g("ék"),
        g("k", "ak", "ek", "ok", "ök"));

    private static final Among OWNED = Among.of(
        g("éi", "é", "ké", "aké", "eké", "oké", "öké"),
        g("ééi", "éké", "éé"),
        g("áéi", "áké"));

    private static final Among SING_OWNER = Among.of(
        g("a", "ja", "d", "ad", "ed", "od", "öd", "e", "je", "nk", "unk", "ünk", "uk", "juk",
            "ük", "jük", "m", "am", "em", "om", "o"),
        g("ád", "ánk", "ájuk", "ám", "á"),
        g("éd", "énk", "éjük", "ém", "é"));

    private static final Among PLUR_OWNER = Among.of(
        g("id", "aid", "jaid", "eid", "jeid", "i", "ai", "jai", "ei", "jei", "itek", "eitek", "jeitek",
            "ik", "aik", "jaik", "eik", "jeik", "ink", "aink", "jaink", "eink", "jeink", "aitok",
            "jaitok", "im", "aim", "jaim", "eim", "jeim"),
        g("áid", "ái", "áik", "áink", "áitok", "áim"),
        g("éid", "éi", "éitek", "éik", "éink", "éim"));

    private int p1;

    public HungarianSnowballStemmer() {
    }

    @Override
    protected void run() {
        int c = cursor;
        markRegions();
        cursor = c;
        limitBackward = cursor;
        cursor = limit;
        int v2 = limit - cursor;
        instrum();
        cursor = limit - v2;
        int v3 = limit - cursor;
        caseSuffix();
        cursor = limit - v3;
        int v4 = limit - cursor;
        caseSpecial();
        cursor = limit - v4;
        int v5 = limit - cursor;
        caseOther();
        cursor = limit - v5;
        int v6 = limit - cursor;
        factive();
        cursor = limit - v6;
        int v7 = limit - cursor;
        owned();
        cursor = limit - v7;
        int v8 = limit - cursor;
        singOwner();
        cursor = limit - v8;
        int v9 = limit - cursor;
        plurOwner();
        cursor = limit - v9;
        int v10 = limit - cursor;
        plural();
        cursor = limit - v10;
        cursor = limitBackward;
    }

    private void markRegions() {
        p1 = limit;
        int v1 = cursor;
        if (inGrouping(V)) {
            int v2 = cursor;
            if (goPastOut(V)) {
                p1 = cursor;
            }
            cursor = v2;
        } else {
            cursor = v1;
            if (goPastIn(V)) {
                p1 = cursor;
            }
        }
    }

    private boolean r1() {
        return p1 <= cursor;
    }

    private boolean isDoubled() {
        int v = limit - cursor;
        if (findAmongB(DOUBLE) == 0) {
            return false;
        }
        cursor = limit - v;
        return true;
    }

    private boolean undouble() {
        if (cursor <= limitBackward) {
            return false;
        }
        cursor--;
        ket = cursor;
        if (cursor <= limitBackward) {
            return false;
        }
        cursor--;
        bra = cursor;
        sliceDel();
        return true;
    }

    private boolean vEnding() {
        ket = cursor;
        int a = findAmongB(V_ENDING);
        if (a == 0) {
            return false;
        }
        bra = cursor;
        if (!r1()) {
            return false;
        }
        switch (a) {
            case 1 -> sliceFrom("a");
            case 2 -> sliceFrom("e");
            default -> {
            }
        }
        return true;
    }

    private boolean instrum() {
        ket = cursor;
        if (findAmongB(INSTRUM) == 0) {
            return false;
        }
        bra = cursor;
        if (!r1()) {
            return false;
        }
        if (!isDoubled()) {
            return false;
        }
        sliceDel();
        return undouble();
    }

    private boolean caseSuffix() {
        ket = cursor;
        if (findAmongB(CASE_) == 0) {
            return false;
        }
        bra = cursor;
        if (!r1()) {
            return false;
        }
        sliceDel();
        return vEnding();
    }

    private boolean caseSpecial() {
        ket = cursor;
        int a = findAmongB(CASE_SPECIAL);
        if (a == 0) {
            return false;
        }
        bra = cursor;
        if (!r1()) {
            return false;
        }
        switch (a) {
            case 1 -> sliceFrom("e");
            case 2 -> sliceFrom("a");
            default -> {
            }
        }
        return true;
    }

    private boolean caseOther() {
        ket = cursor;
        int a = findAmongB(CASE_OTHER);
        if (a == 0) {
            return false;
        }
        bra = cursor;
        if (!r1()) {
            return false;
        }
        switch (a) {
            case 1 -> sliceDel();
            case 2 -> sliceFrom("a");
            case 3 -> sliceFrom("e");
            default -> {
            }
        }
        return true;
    }

    private boolean factive() {
        ket = cursor;
        if (findAmongB(FACTIVE) == 0) {
            return false;
        }
        bra = cursor;
        if (!r1()) {
            return false;
        }
        if (!isDoubled()) {
            return false;
        }
        sliceDel();
        return undouble();
    }

    private boolean plural() {
        ket = cursor;
        int a = findAmongB(PLURAL);
        if (a == 0) {
            return false;
        }
        bra = cursor;
        if (!r1()) {
            return false;
        }
        switch (a) {
            case 1 -> sliceFrom("a");
            case 2 -> sliceFrom("e");
            case 3 -> sliceDel();
            default -> {
            }
        }
        return true;
    }

    private boolean owned() {
        ket = cursor;
        int a = findAmongB(OWNED);
        if (a == 0) {
            return false;
        }
        bra = cursor;
        if (!r1()) {
            return false;
        }
        switch (a) {
            case 1 -> sliceDel();
            case 2 -> sliceFrom("e");
            case 3 -> sliceFrom("a");
            default -> {
            }
        }
        return true;
    }

    private boolean singOwner() {
        ket = cursor;
        int a = findAmongB(SING_OWNER);
        if (a == 0) {
            return false;
        }
        bra = cursor;
        if (!r1()) {
            return false;
        }
        switch (a) {
            case 1 -> sliceDel();
            case 2 -> sliceFrom("a");
            case 3 -> sliceFrom("e");
            default -> {
            }
        }
        return true;
    }

    private boolean plurOwner() {
        ket = cursor;
        int a = findAmongB(PLUR_OWNER);
        if (a == 0) {
            return false;
        }
        bra = cursor;
        if (!r1()) {
            return false;
        }
        switch (a) {
            case 1 -> sliceDel();
            case 2 -> sliceFrom("a");
            case 3 -> sliceFrom("e");
            default -> {
            }
        }
        return true;
    }
}
