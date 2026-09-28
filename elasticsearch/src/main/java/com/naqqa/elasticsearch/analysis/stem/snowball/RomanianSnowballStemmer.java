package com.naqqa.elasticsearch.analysis.stem.snowball;

public final class RomanianSnowballStemmer extends SnowballSupport {

    private static final String V = "aeiouăâî";

    private static final Among POSTLUDE = Among.of(g("I"), g("U"), g(""));

    private static final Among STEP0 = Among.of(
        g("ul", "ului"),
        g("aua"),
        g("ea", "ele", "elor"),
        g("ii", "iua", "iei", "iile", "iilor", "ilor"),
        g("ile"),
        g("atei"),
        g("ație", "ația"));

    private static final Among COMBO = Among.of(
        g("abilitate", "abilitati", "abilităi", "abilități"),
        g("ibilitate"),
        g("ivitate", "ivitati", "ivităi", "ivități"),
        g("icitate", "icitati", "icităi", "icități",
            "icator", "icatori", "iciv", "iciva", "icive", "icivi", "icivă",
            "ical", "icala", "icale", "icali", "icală"),
        g("ativ", "ativa", "ative", "ativi", "ativă", "ațiune",
            "atoare", "ator", "atori", "ătoare", "ător", "ători"),
        g("itiv", "itiva", "itive", "itivi", "itivă", "ițiune",
            "itoare", "itor", "itori"));

    private static final Among STANDARD = Among.of(
        g("ica", "abila", "ibila", "oasa", "ata", "ita", "anta", "uta", "iva",
            "ic", "ice", "abile", "ibile", "oase", "ate", "itate", "ite", "ante",
            "ute", "ive", "ici", "abili", "ibili", "atori", "osi", "ati", "itati",
            "iti", "anti", "uti", "ivi", "ităi", "oși", "ități",
            "abil", "ibil", "ator", "os", "at", "it", "ant", "ut", "iv",
            "ică", "abilă", "ibilă", "oasă", "ată",
            "ită", "antă", "ută", "ivă"),
        g("iune", "iuni"),
        g("ista", "isme", "iste", "isti", "iști", "ism", "ist", "istă"));

    private static final Among VERB = Among.of(
        g("ea", "ia", "esc", "ăsc", "ind", "ând", "are", "ere", "ire", "âre",
            "ase", "ise", "use", "âse", "ește", "ăște", "eze",
            "ai", "eai", "iai", "ești", "ăști", "ui", "ezi", "âi",
            "ași", "aseși", "iseși", "useși", "âseși",
            "iși", "uși", "âși",
            "eați", "iați", "arăți", "aserăți",
            "iserăți", "userăți", "âserăți",
            "irăți", "urăți", "ârăți",
            "am", "eam", "iam", "asem", "isem", "usem", "âsem",
            "arăm", "aserăm", "iserăm", "userăm", "âserăm",
            "irăm", "urăm", "ârăm",
            "au", "eau", "iau", "indu", "ându", "ez", "ească",
            "ară", "aseră", "iseră", "useră", "âseră",
            "iră", "ură", "âră", "ează"),
        g("se", "sese", "sei", "seși", "seseși",
            "ați", "eți", "iți", "âți",
            "serăți", "seserăți",
            "em", "sesem", "im", "âm", "ăm",
            "serăm", "seserăm", "seră", "seseră"));

    private static final Among VOWEL = Among.of(g("a", "e", "ie", "i", "ă"));

    private int pV;
    private int p1;
    private int p2;
    private boolean standardSuffixRemoved;

    public RomanianSnowballStemmer() {
    }

    @Override
    protected void run() {
        norm();
        int c = cursor;
        prelude();
        cursor = c;
        markRegions();
        cursor = c;
        limitBackward = cursor;
        cursor = limit;
        int v = limit - cursor;
        step0();
        cursor = limit - v;
        standardSuffix();
        cursor = limit - v;
        if (!standardSuffixRemoved) {
            verbSuffix();
        }
        cursor = limit - v;
        vowelSuffix();
        cursor = limit - v;
        cursor = limitBackward;
        postlude();
    }

    private void norm() {
        int len = current.length();
        for (int i = 0; i < len; i++) {
            char ch = current.charAt(i);
            if (ch == 'ş') {
                current.setCharAt(i, 'ș');
            } else if (ch == 'ţ') {
                current.setCharAt(i, 'ț');
            }
        }
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
        }
        cursor = c;
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

    private boolean step0() {
        ket = cursor;
        int a = findAmongB(STEP0);
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
            case 4 -> sliceFrom("i");
            case 5 -> {
                if (eqSB("ab")) {
                    return false;
                }
                sliceFrom("i");
            }
            case 6 -> sliceFrom("at");
            default -> sliceFrom("ați");
        }
        return true;
    }

    private boolean comboSuffix() {
        int v = limit - cursor;
        ket = cursor;
        int a = findAmongB(COMBO);
        if (a == 0) {
            return false;
        }
        bra = cursor;
        if (!r1()) {
            return false;
        }
        switch (a) {
            case 1 -> sliceFrom("abil");
            case 2 -> sliceFrom("ibil");
            case 3 -> sliceFrom("iv");
            case 4 -> sliceFrom("ic");
            case 5 -> sliceFrom("at");
            default -> sliceFrom("it");
        }
        standardSuffixRemoved = true;
        cursor = limit - v;
        return true;
    }

    private boolean standardSuffix() {
        standardSuffixRemoved = false;
        while (true) {
            int v = limit - cursor;
            if (!comboSuffix()) {
                cursor = limit - v;
                break;
            }
        }
        ket = cursor;
        int a = findAmongB(STANDARD);
        if (a == 0) {
            return false;
        }
        bra = cursor;
        if (!r2()) {
            return false;
        }
        switch (a) {
            case 1 -> sliceDel();
            case 2 -> {
                if (!eqSB("ț")) {
                    return false;
                }
                bra = cursor;
                sliceFrom("t");
            }
            default -> sliceFrom("ist");
        }
        standardSuffixRemoved = true;
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
            int a = findAmongB(VERB);
            if (a == 0) {
                return false;
            }
            bra = cursor;
            if (a == 1) {
                int v = limit - cursor;
                if (!outGroupingB(V)) {
                    cursor = limit - v;
                    if (!eqSB("u")) {
                        return false;
                    }
                }
            }
            sliceDel();
            return true;
        } finally {
            limitBackward = lb;
        }
    }

    private boolean vowelSuffix() {
        ket = cursor;
        if (findAmongB(VOWEL) == 0) {
            return false;
        }
        bra = cursor;
        if (!rv()) {
            return false;
        }
        sliceDel();
        return true;
    }
}
