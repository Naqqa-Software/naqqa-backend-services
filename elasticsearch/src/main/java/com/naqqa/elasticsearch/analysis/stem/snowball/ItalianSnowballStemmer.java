package com.naqqa.elasticsearch.analysis.stem.snowball;

public final class ItalianSnowballStemmer extends SnowballSupport {

    private static final String V = "aeiouàèìòù";
    private static final String AEIO = "aeioàèìò";
    private static final String CG = "cg";

    private static final Among PRELUDE = Among.of(
        g("á"), g("é"), g("í"), g("ó"), g("ú"), g("qu"), g(""));

    private static final Among POSTLUDE = Among.of(g("I"), g("U"), g(""));

    private static final Among PRONOUN = Among.of(g(
        "ci", "gli", "la", "le", "li", "lo", "mi", "ne", "si", "ti", "vi",
        "sene", "gliela", "gliele", "glieli", "glielo", "gliene",
        "mela", "mele", "meli", "melo", "mene",
        "tela", "tele", "teli", "telo", "tene",
        "cela", "cele", "celi", "celo", "cene",
        "vela", "vele", "veli", "velo", "vene"));

    private static final Among PRONOUN_BASE = Among.of(g("ando", "endo"), g("ar", "er", "ir"));

    private static final Among STANDARD = Among.of(
        g("anza", "anze", "ico", "ici", "ica", "ice", "iche", "ichi", "ismo",
            "ismi", "abile", "abili", "ibile", "ibili", "ista", "iste", "isti",
            "istà", "istè", "istì", "oso", "osi", "osa", "ose", "mente",
            "atrice", "atrici", "ante", "anti"),
        g("azione", "azioni", "atore", "atori"),
        g("logia", "logie"),
        g("uzione", "uzioni", "usione", "usioni"),
        g("enza", "enze"),
        g("amento", "amenti", "imento", "imenti"),
        g("amente"),
        g("ità"),
        g("ivo", "ivi", "iva", "ive"));

    private static final Among AMENTE = Among.of(g("iv"), g("os", "ic", "abil"));

    private static final Among ITA = Among.of(g("abil", "ic", "iv"));

    private static final Among VERB = Among.of(g(
        "ammo", "ando", "ano", "are", "arono", "asse", "assero", "assi",
        "assimo", "ata", "ate", "ati", "ato", "ava", "avamo", "avano", "avate",
        "avi", "avo", "emmo", "enda", "ende", "endi", "endo", "erà", "erai",
        "eranno", "ere", "erebbe", "erebbero", "erei", "eremmo", "eremo",
        "ereste", "eresti", "erete", "erò", "erono", "essero", "ete",
        "eva", "evamo", "evano", "evate", "evi", "evo", "Yamo", "iamo", "immo",
        "irà", "irai", "iranno", "ire", "irebbe", "irebbero", "irei",
        "iremmo", "iremo", "ireste", "iresti", "irete", "irò", "irono",
        "isca", "iscano", "isce", "isci", "isco", "iscono", "issero", "ita",
        "ite", "iti", "ito", "iva", "ivamo", "ivano", "ivate", "ivi", "ivo",
        "ono", "uta", "ute", "uti", "uto", "ar", "ir"));

    private int pV;
    private int p1;
    private int p2;

    public ItalianSnowballStemmer() {
    }

    @Override
    protected void run() {
        int c = cursor;
        prelude();
        cursor = c;
        markRegions();
        cursor = c;
        limitBackward = cursor;
        cursor = limit;
        int v = limit - cursor;
        attachedPronoun();
        cursor = limit - v;
        if (!standardSuffix()) {
            cursor = limit - v;
            verbSuffix();
        }
        cursor = limit - v;
        vowelSuffix();
        cursor = limitBackward;
        postlude();
    }

    private void prelude() {
        int c0 = cursor;
        while (true) {
            int c = cursor;
            bra = cursor;
            int a = findAmong(PRELUDE);
            if (a == 0) {
                cursor = c;
                break;
            }
            ket = cursor;
            boolean stop = false;
            switch (a) {
                case 1 -> sliceFrom("à");
                case 2 -> sliceFrom("è");
                case 3 -> sliceFrom("ì");
                case 4 -> sliceFrom("ò");
                case 5 -> sliceFrom("ù");
                case 6 -> sliceFrom("qU");
                default -> {
                    if (cursor >= limit) {
                        stop = true;
                    } else {
                        cursor++;
                    }
                }
            }
            if (stop) {
                cursor = c;
                break;
            }
        }
        cursor = c0;
        while (true) {
            int c1 = cursor;
            if (!gotoMark()) {
                cursor = c1;
                return;
            }
        }
    }

    private boolean gotoMark() {
        while (true) {
            int c = cursor;
            if (markAt()) {
                cursor = c;
                return true;
            }
            cursor = c;
            if (cursor >= limit) {
                return false;
            }
            cursor++;
        }
    }

    private boolean markAt() {
        if (!inGrouping(V)) {
            return false;
        }
        bra = cursor;
        int c = cursor;
        if (eqS("u")) {
            ket = cursor;
            if (inGrouping(V)) {
                sliceFrom("U");
                return true;
            }
        }
        cursor = c;
        if (eqS("i")) {
            ket = cursor;
            if (inGrouping(V)) {
                sliceFrom("I");
                return true;
            }
        }
        return false;
    }

    private void markRegions() {
        pV = limit;
        p1 = limit;
        p2 = limit;
        int c = cursor;
        rv:
        {
            if (inGrouping(V)) {
                int c2 = cursor;
                if (outGrouping(V) && goPastIn(V)) {
                    pV = cursor;
                    break rv;
                }
                cursor = c2;
                if (inGrouping(V) && goPastOut(V)) {
                    pV = cursor;
                    break rv;
                }
            }
            cursor = c;
            if (outGrouping(V)) {
                int c2 = cursor;
                if (outGrouping(V) && goPastIn(V)) {
                    pV = cursor;
                    break rv;
                }
                cursor = c2;
                if (inGrouping(V) && next()) {
                    pV = cursor;
                }
            }
        }
        cursor = c;
        if (goPastIn(V) && goPastOut(V)) {
            p1 = cursor;
            if (goPastIn(V) && goPastOut(V)) {
                p2 = cursor;
            }
        }
        cursor = c;
    }

    private void postlude() {
        while (true) {
            int c = cursor;
            bra = cursor;
            int a = findAmong(POSTLUDE);
            if (a == 0) {
                cursor = c;
                return;
            }
            ket = cursor;
            switch (a) {
                case 1 -> sliceFrom("i");
                case 2 -> sliceFrom("u");
                default -> {
                    if (cursor >= limit) {
                        cursor = c;
                        return;
                    }
                    cursor++;
                }
            }
        }
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

    private void attachedPronoun() {
        ket = cursor;
        if (findAmongB(PRONOUN) == 0) {
            return;
        }
        bra = cursor;
        int a = findAmongB(PRONOUN_BASE);
        if (a == 0 || !rv()) {
            return;
        }
        if (a == 1) {
            sliceDel();
        } else {
            sliceFrom("e");
        }
    }

    private void tryR2Delete(String s) {
        int v = limit - cursor;
        ket = cursor;
        if (eqSB(s)) {
            bra = cursor;
            if (r2()) {
                sliceDel();
                return;
            }
        }
        cursor = limit - v;
    }

    private boolean standardSuffix() {
        ket = cursor;
        int a = findAmongB(STANDARD);
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
            case 2 -> {
                if (!r2()) {
                    return false;
                }
                sliceDel();
                tryR2Delete("ic");
            }
            case 3 -> {
                if (!r2()) {
                    return false;
                }
                sliceFrom("log");
            }
            case 4 -> {
                if (!r2()) {
                    return false;
                }
                sliceFrom("u");
            }
            case 5 -> {
                if (!r2()) {
                    return false;
                }
                sliceFrom("ente");
            }
            case 6 -> {
                if (!rv()) {
                    return false;
                }
                sliceDel();
            }
            case 7 -> {
                if (!r1()) {
                    return false;
                }
                sliceDel();
                int v = limit - cursor;
                ket = cursor;
                int b = findAmongB(AMENTE);
                if (b == 0) {
                    cursor = limit - v;
                    break;
                }
                bra = cursor;
                if (!r2()) {
                    cursor = limit - v;
                    break;
                }
                sliceDel();
                if (b == 1) {
                    ket = cursor;
                    if (!eqSB("at")) {
                        cursor = limit - v;
                        break;
                    }
                    bra = cursor;
                    if (!r2()) {
                        cursor = limit - v;
                        break;
                    }
                    sliceDel();
                }
            }
            case 8 -> {
                if (!r2()) {
                    return false;
                }
                sliceDel();
                int v = limit - cursor;
                ket = cursor;
                if (findAmongB(ITA) != 0) {
                    bra = cursor;
                    if (r2()) {
                        sliceDel();
                        break;
                    }
                }
                cursor = limit - v;
            }
            default -> {
                if (!r2()) {
                    return false;
                }
                sliceDel();
                int v = limit - cursor;
                ket = cursor;
                if (eqSB("at")) {
                    bra = cursor;
                    if (r2()) {
                        sliceDel();
                        ket = cursor;
                        if (eqSB("ic")) {
                            bra = cursor;
                            if (r2()) {
                                sliceDel();
                                break;
                            }
                        }
                    }
                }
                cursor = limit - v;
            }
        }
        return true;
    }

    private boolean verbSuffix() {
        if (cursor < pV) {
            return false;
        }
        int lb = limitBackward;
        limitBackward = pV;
        try {
            ket = cursor;
            if (findAmongB(VERB) == 0) {
                return false;
            }
            bra = cursor;
            sliceDel();
            return true;
        } finally {
            limitBackward = lb;
        }
    }

    private void vowelSuffix() {
        int v = limit - cursor;
        ket = cursor;
        tryA:
        {
            if (!inGroupingB(AEIO)) {
                break tryA;
            }
            bra = cursor;
            if (!rv()) {
                break tryA;
            }
            sliceDel();
            ket = cursor;
            if (!eqSB("i")) {
                break tryA;
            }
            bra = cursor;
            if (!rv()) {
                break tryA;
            }
            sliceDel();
            v = limit - cursor;
        }
        cursor = limit - v;
        ket = cursor;
        if (eqSB("h")) {
            bra = cursor;
            if (inGroupingB(CG) && rv()) {
                sliceDel();
            }
        }
    }
}
