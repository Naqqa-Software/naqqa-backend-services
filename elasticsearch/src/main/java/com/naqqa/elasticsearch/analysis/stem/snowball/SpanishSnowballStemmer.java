package com.naqqa.elasticsearch.analysis.stem.snowball;

public final class SpanishSnowballStemmer extends SnowballSupport {

    private static final String V = "aeiouáéíóúü";

    private static final Among POSTLUDE = Among.of(
        g("á"), g("é"), g("í"), g("ó"), g("ú"), g(""));

    private static final Among PRONOUN = Among.of(g(
        "me", "se", "sela", "selo", "selas", "selos", "la", "le", "lo",
        "las", "les", "los", "nos"));

    private static final Among PRONOUN_BASE = Among.of(
        g("iéndo"),
        g("ándo"),
        g("ár"),
        g("ér"),
        g("ír"),
        g("ando", "iendo", "ar", "er", "ir"),
        g("yendo"));

    private static final Among STANDARD = Among.of(
        g("anza", "anzas", "ico", "ica", "icos", "icas", "ismo", "ismos", "able", "ables",
            "ible", "ibles", "ista", "istas", "oso", "osa", "osos", "osas",
            "amiento", "amientos", "imiento", "imientos"),
        g("adora", "ador", "ación", "adoras", "adores", "aciones",
            "ante", "antes", "ancia", "ancias"),
        g("logía", "logías"),
        g("ución", "uciones"),
        g("encia", "encias"),
        g("amente"),
        g("mente"),
        g("idad", "idades"),
        g("iva", "ivo", "ivas", "ivos"));

    private static final Among AMENTE = Among.of(g("iv"), g("os", "ic", "ad"));

    private static final Among MENTE = Among.of(g("ante", "able", "ible"));

    private static final Among IDAD = Among.of(g("abil", "ic", "iv"));

    private static final Among Y_VERB = Among.of(g(
        "ya", "ye", "yan", "yen", "yeron", "yendo", "yo", "yó",
        "yas", "yes", "yais", "yamos"));

    private static final Among VERB = Among.of(
        g("en", "es", "éis", "emos"),
        g("arían", "arías", "arán", "arás", "aríais",
            "aría", "aréis", "aríamos", "aremos", "ará",
            "aré",
            "erían", "erías", "erán", "erás", "eríais",
            "ería", "eréis", "eríamos", "eremos", "erá",
            "eré",
            "irían", "irías", "irán", "irás", "iríais",
            "iría", "iréis", "iríamos", "iremos", "irá",
            "iré",
            "aba", "ada", "ida", "ía", "ara", "iera", "ad", "ed",
            "id", "ase", "iese", "aste", "iste", "an", "aban", "ían",
            "aran", "ieran", "asen", "iesen", "aron", "ieron", "ado",
            "ido", "ando", "iendo", "ió", "ar", "er", "ir", "as",
            "abas", "adas", "idas", "ías", "aras", "ieras", "ases",
            "ieses", "ís", "áis", "abais", "íais", "arais",
            "ierais", "aseis", "ieseis", "asteis", "isteis", "ados",
            "idos", "amos", "ábamos", "íamos", "imos",
            "áramos", "iéramos", "iésemos", "ásemos"));

    private static final Among RESIDUAL = Among.of(
        g("os", "a", "o", "á", "í", "ó"),
        g("e", "é"));

    private int pV;
    private int p1;
    private int p2;

    public SpanishSnowballStemmer() {
    }

    @Override
    protected void run() {
        int c = cursor;
        markRegions();
        cursor = c;
        limitBackward = cursor;
        cursor = limit;
        int v = limit - cursor;
        attachedPronoun();
        cursor = limit - v;
        if (!standardSuffix()) {
            cursor = limit - v;
            if (!yVerbSuffix()) {
                cursor = limit - v;
                verbSuffix();
            }
        }
        cursor = limit - v;
        residualSuffix();
        cursor = limitBackward;
        postlude();
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
                case 1 -> sliceFrom("a");
                case 2 -> sliceFrom("e");
                case 3 -> sliceFrom("i");
                case 4 -> sliceFrom("o");
                case 5 -> sliceFrom("u");
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
        switch (a) {
            case 1 -> {
                bra = cursor;
                sliceFrom("iendo");
            }
            case 2 -> {
                bra = cursor;
                sliceFrom("ando");
            }
            case 3 -> {
                bra = cursor;
                sliceFrom("ar");
            }
            case 4 -> {
                bra = cursor;
                sliceFrom("er");
            }
            case 5 -> {
                bra = cursor;
                sliceFrom("ir");
            }
            case 6 -> sliceDel();
            default -> {
                if (eqSB("u")) {
                    sliceDel();
                }
            }
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
            case 7 -> {
                if (!r2()) {
                    return false;
                }
                sliceDel();
                tryAmongR2Delete(MENTE);
            }
            case 8 -> {
                if (!r2()) {
                    return false;
                }
                sliceDel();
                tryAmongR2Delete(IDAD);
            }
            default -> {
                if (!r2()) {
                    return false;
                }
                sliceDel();
                tryR2Delete("at");
            }
        }
        return true;
    }

    private void tryAmongR2Delete(Among among) {
        int v = limit - cursor;
        ket = cursor;
        if (findAmongB(among) != 0) {
            bra = cursor;
            if (r2()) {
                sliceDel();
                return;
            }
        }
        cursor = limit - v;
    }

    private boolean yVerbSuffix() {
        if (cursor < pV) {
            return false;
        }
        int lb = limitBackward;
        limitBackward = pV;
        ket = cursor;
        int a = findAmongB(Y_VERB);
        if (a == 0) {
            limitBackward = lb;
            return false;
        }
        bra = cursor;
        limitBackward = lb;
        if (!eqSB("u")) {
            return false;
        }
        sliceDel();
        return true;
    }

    private boolean verbSuffix() {
        if (cursor < pV) {
            return false;
        }
        int lb = limitBackward;
        limitBackward = pV;
        ket = cursor;
        int a = findAmongB(VERB);
        if (a == 0) {
            limitBackward = lb;
            return false;
        }
        bra = cursor;
        limitBackward = lb;
        if (a == 1) {
            int v = limit - cursor;
            if (eqSB("u")) {
                int v2 = limit - cursor;
                if (eqSB("g")) {
                    cursor = limit - v2;
                } else {
                    cursor = limit - v;
                }
            } else {
                cursor = limit - v;
            }
            bra = cursor;
        }
        sliceDel();
        return true;
    }

    private void residualSuffix() {
        ket = cursor;
        int a = findAmongB(RESIDUAL);
        if (a == 0) {
            return;
        }
        bra = cursor;
        if (!rv()) {
            return;
        }
        sliceDel();
        if (a == 2) {
            int v = limit - cursor;
            ket = cursor;
            if (eqSB("u")) {
                bra = cursor;
                int v2 = limit - cursor;
                if (eqSB("g")) {
                    cursor = limit - v2;
                    if (rv()) {
                        sliceDel();
                        return;
                    }
                }
            }
            cursor = limit - v;
        }
    }
}
