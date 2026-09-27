package com.naqqa.elasticsearch.analysis.stem.snowball;

public final class FrenchSnowballStemmer extends SnowballSupport {

    private static final String V = "aeiouyâàëéêèïîôûù";
    private static final String KEEP_WITH_S = "aiouès";

    private static final Among START = Among.of(g("col", "par", "tap"));

    private static final Among POSTLUDE = Among.of(
        g("I"), g("U"), g("Y"), g("He"), g("Hi"), g("H"), g(""));

    private static final Among STANDARD = Among.of(
        g("ance", "iqUe", "isme", "able", "iste", "eux", "ances", "iqUes", "ismes", "ables", "istes"),
        g("atrice", "ateur", "ation", "atrices", "ateurs", "ations"),
        g("logie", "logies"),
        g("usion", "ution", "usions", "utions"),
        g("ence", "ences"),
        g("ement", "ements"),
        g("ité", "ités"),
        g("if", "ive", "ifs", "ives"),
        g("eaux"),
        g("aux"),
        g("euse", "euses"),
        g("issement", "issements"),
        g("amment"),
        g("emment"),
        g("ment", "ments"));

    private static final Among EMENT = Among.of(
        g("iv"), g("eus"), g("abl", "iqU"), g("ièr", "Ièr"));

    private static final Among ITE = Among.of(g("abil"), g("ic"), g("iv"));

    private static final Among I_VERB = Among.of(g(
        "îmes", "ît", "îtes", "i", "ie", "ies", "ir", "ira", "irai",
        "iraIent", "irais", "irait", "iras", "irent", "irez", "iriez",
        "irions", "irons", "iront", "is", "issaIent", "issais", "issait",
        "issant", "issante", "issantes", "issants", "isse", "issent", "isses",
        "issez", "issiez", "issions", "issons", "it"));

    private static final Among VERB = Among.of(
        g("ions"),
        g("é", "ée", "ées", "és", "èrent", "er", "era", "erai",
            "eraIent", "erais", "erait", "eras", "erez", "eriez", "erions",
            "erons", "eront", "ez", "iez"),
        g("âmes", "ât", "âtes", "a", "ai", "aIent", "ais", "ait", "ant",
            "ante", "antes", "ants", "as", "asse", "assent", "asses", "assiez",
            "assions"));

    private static final Among RESIDUAL = Among.of(
        g("ion"), g("ier", "ière", "Ier", "Ière"), g("e"));

    private static final Among UN_DOUBLE = Among.of(g("enn", "onn", "ett", "ell", "eill"));

    private int pV;
    private int p1;
    private int p2;

    public FrenchSnowballStemmer() {
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
        int v1 = limit - cursor;
        mainSuffixes();
        cursor = limit - v1;
        unDouble();
        cursor = limit - v1;
        unAccent();
        cursor = limitBackward;
        postlude();
    }

    private void prelude() {
        while (true) {
            int c1 = cursor;
            if (!gotoPreludeMatch()) {
                cursor = c1;
                return;
            }
        }
    }

    private boolean gotoPreludeMatch() {
        while (true) {
            int c = cursor;
            if (preludeAt()) {
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

    private boolean preludeAt() {
        int c = cursor;
        if (inGrouping(V)) {
            bra = cursor;
            int c2 = cursor;
            if (eqS("u")) {
                ket = cursor;
                if (inGrouping(V)) {
                    sliceFrom("U");
                    return true;
                }
            }
            cursor = c2;
            if (eqS("i")) {
                ket = cursor;
                if (inGrouping(V)) {
                    sliceFrom("I");
                    return true;
                }
            }
            cursor = c2;
            if (eqS("y")) {
                ket = cursor;
                sliceFrom("Y");
                return true;
            }
        }
        cursor = c;
        bra = cursor;
        if (eqS("ë")) {
            ket = cursor;
            sliceFrom("He");
            return true;
        }
        cursor = c;
        bra = cursor;
        if (eqS("ï")) {
            ket = cursor;
            sliceFrom("Hi");
            return true;
        }
        cursor = c;
        bra = cursor;
        if (eqS("y")) {
            ket = cursor;
            if (inGrouping(V)) {
                sliceFrom("Y");
                return true;
            }
        }
        cursor = c;
        if (eqS("q")) {
            bra = cursor;
            if (eqS("u")) {
                ket = cursor;
                sliceFrom("U");
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
        if (inGrouping(V) && inGrouping(V) && next()) {
            pV = cursor;
        } else {
            cursor = c;
            if (findAmong(START) != 0) {
                pV = cursor;
            } else {
                cursor = c;
                if (next() && goPastIn(V)) {
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
                case 3 -> sliceFrom("y");
                case 4 -> sliceFrom("ë");
                case 5 -> sliceFrom("ï");
                case 6 -> sliceDel();
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

    private void mainSuffixes() {
        int v2 = limit - cursor;
        boolean ok = standardSuffix();
        if (!ok) {
            cursor = limit - v2;
            ok = iVerbSuffix();
        }
        if (!ok) {
            cursor = limit - v2;
            ok = verbSuffix();
        }
        if (ok) {
            cursor = limit - v2;
            ket = cursor;
            int v3 = limit - cursor;
            if (eqSB("Y")) {
                bra = cursor;
                sliceFrom("i");
            } else {
                cursor = limit - v3;
                if (eqSB("ç")) {
                    bra = cursor;
                    sliceFrom("c");
                }
            }
            return;
        }
        cursor = limit - v2;
        residualSuffix();
    }

    private boolean r2DeleteOrReplace(String replacement) {
        int v = limit - cursor;
        if (r2()) {
            sliceDel();
        } else {
            cursor = limit - v;
            sliceFrom(replacement);
        }
        return true;
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
                int v = limit - cursor;
                ket = cursor;
                if (eqSB("ic")) {
                    bra = cursor;
                    r2DeleteOrReplace("iqU");
                } else {
                    cursor = limit - v;
                }
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
                sliceFrom("ent");
            }
            case 6 -> {
                if (!rv()) {
                    return false;
                }
                sliceDel();
                ementTail();
            }
            case 7 -> {
                if (!r2()) {
                    return false;
                }
                sliceDel();
                int v = limit - cursor;
                ket = cursor;
                int b = findAmongB(ITE);
                if (b == 0) {
                    cursor = limit - v;
                } else {
                    bra = cursor;
                    switch (b) {
                        case 1 -> r2DeleteOrReplace("abl");
                        case 2 -> r2DeleteOrReplace("iqU");
                        default -> {
                            if (r2()) {
                                sliceDel();
                            } else {
                                cursor = limit - v;
                            }
                        }
                    }
                }
            }
            case 8 -> {
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
                            r2DeleteOrReplace("iqU");
                        } else {
                            cursor = limit - v;
                        }
                    } else {
                        cursor = limit - v;
                    }
                } else {
                    cursor = limit - v;
                }
            }
            case 9 -> sliceFrom("eau");
            case 10 -> {
                if (!r1()) {
                    return false;
                }
                sliceFrom("al");
            }
            case 11 -> {
                int v = limit - cursor;
                if (r2()) {
                    sliceDel();
                } else {
                    cursor = limit - v;
                    if (!r1()) {
                        return false;
                    }
                    sliceFrom("eux");
                }
            }
            case 12 -> {
                if (!r1()) {
                    return false;
                }
                if (!outGroupingB(V)) {
                    return false;
                }
                sliceDel();
            }
            case 13 -> {
                if (!rv()) {
                    return false;
                }
                sliceFrom("ant");
                return false;
            }
            case 14 -> {
                if (!rv()) {
                    return false;
                }
                sliceFrom("ent");
                return false;
            }
            default -> {
                int v = limit - cursor;
                if (!inGroupingB(V)) {
                    return false;
                }
                if (!rv()) {
                    return false;
                }
                cursor = limit - v;
                sliceDel();
                return false;
            }
        }
        return true;
    }

    private void ementTail() {
        int v = limit - cursor;
        ket = cursor;
        int b = findAmongB(EMENT);
        if (b == 0) {
            cursor = limit - v;
            return;
        }
        bra = cursor;
        switch (b) {
            case 1 -> {
                if (!r2()) {
                    cursor = limit - v;
                    return;
                }
                sliceDel();
                ket = cursor;
                if (!eqSB("at")) {
                    cursor = limit - v;
                    return;
                }
                bra = cursor;
                if (!r2()) {
                    cursor = limit - v;
                    return;
                }
                sliceDel();
            }
            case 2 -> {
                int v2 = limit - cursor;
                if (r2()) {
                    sliceDel();
                } else {
                    cursor = limit - v2;
                    if (!r1()) {
                        cursor = limit - v;
                        return;
                    }
                    sliceFrom("eux");
                }
            }
            case 3 -> {
                if (!r2()) {
                    cursor = limit - v;
                    return;
                }
                sliceDel();
            }
            default -> {
                if (!rv()) {
                    cursor = limit - v;
                    return;
                }
                sliceFrom("i");
            }
        }
    }

    private boolean iVerbSuffix() {
        if (cursor < pV) {
            return false;
        }
        int lb = limitBackward;
        limitBackward = pV;
        try {
            ket = cursor;
            if (findAmongB(I_VERB) == 0) {
                return false;
            }
            bra = cursor;
            int v = limit - cursor;
            if (eqSB("H")) {
                return false;
            }
            cursor = limit - v;
            if (!outGroupingB(V)) {
                return false;
            }
            sliceDel();
            return true;
        } finally {
            limitBackward = lb;
        }
    }

    private boolean verbSuffix() {
        if (cursor < pV) {
            return false;
        }
        int lb = limitBackward;
        limitBackward = pV;
        try {
            ket = cursor;
            int a = findAmongB(VERB);
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
                case 2 -> sliceDel();
                default -> {
                    sliceDel();
                    int v = limit - cursor;
                    ket = cursor;
                    if (eqSB("e")) {
                        bra = cursor;
                        sliceDel();
                    } else {
                        cursor = limit - v;
                    }
                }
            }
            return true;
        } finally {
            limitBackward = lb;
        }
    }

    private void residualSuffix() {
        int v1 = limit - cursor;
        ket = cursor;
        if (eqSB("s")) {
            bra = cursor;
            int v2 = limit - cursor;
            boolean t = eqSB("Hi");
            if (!t) {
                cursor = limit - v2;
                t = outGroupingB(KEEP_WITH_S);
            }
            cursor = limit - v2;
            if (t) {
                sliceDel();
            } else {
                cursor = limit - v1;
            }
        } else {
            cursor = limit - v1;
        }
        if (cursor < pV) {
            return;
        }
        int lb = limitBackward;
        limitBackward = pV;
        try {
            ket = cursor;
            int a = findAmongB(RESIDUAL);
            if (a == 0) {
                return;
            }
            bra = cursor;
            switch (a) {
                case 1 -> {
                    if (!r2()) {
                        return;
                    }
                    int v = limit - cursor;
                    if (!eqSB("s")) {
                        cursor = limit - v;
                        if (!eqSB("t")) {
                            return;
                        }
                    }
                    sliceDel();
                }
                case 2 -> sliceFrom("i");
                default -> sliceDel();
            }
        } finally {
            limitBackward = lb;
        }
    }

    private void unDouble() {
        int v = limit - cursor;
        if (findAmongB(UN_DOUBLE) == 0) {
            return;
        }
        cursor = limit - v;
        ket = cursor;
        if (!nextB()) {
            return;
        }
        bra = cursor;
        sliceDel();
    }

    private void unAccent() {
        int count = 1;
        while (true) {
            int v = limit - cursor;
            if (!outGroupingB(V)) {
                cursor = limit - v;
                break;
            }
            count--;
        }
        if (count > 0) {
            return;
        }
        ket = cursor;
        int v = limit - cursor;
        if (!eqSB("é")) {
            cursor = limit - v;
            if (!eqSB("è")) {
                return;
            }
        }
        bra = cursor;
        sliceFrom("e");
    }
}
