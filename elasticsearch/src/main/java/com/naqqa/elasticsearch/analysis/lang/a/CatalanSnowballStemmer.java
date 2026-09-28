package com.naqqa.elasticsearch.analysis.lang.a;

import com.naqqa.elasticsearch.analysis.stem.snowball.SnowballSupport;

public final class CatalanSnowballStemmer extends SnowballSupport {

    private static final String V = "aeiouàáèéíïòóúü";

    private static final Among CLEANING = Among.of(
        g("à", "á"),
        g("è", "é"),
        g("ì", "í", "ï"),
        g("ò", "ó"),
        g("ú", "ü"),
        g("·"),
        g(""));

    private static final Among ATTACHED_PRONOUN = Among.of(g(
        "la", "-la", "sela", "le", "me", "-me", "se", "-te", "hi", "'hi", "li", "-li", "'l", "'m", "-m",
        "'n", "-n", "ho", "'ho", "lo", "selo", "'s", "las", "selas", "les", "-les", "'ls", "-ls", "'ns",
        "-ns", "ens", "los", "selos", "nos", "-nos", "vos", "us", "-us", "'t"));

    private static final Among STANDARD = Among.of(
        g(
            "enca", "ancia", "encia", "ència", "ícia", "inia", "íinia", "eria",
            "ària", "atòria", "alla", "ella", "ívola", "ima", "íssima", "ana",
            "ina", "era", "sfera", "ora", "dora", "adora", "adura", "esa",
            "osa", "assa", "essa", "issa", "eta", "ita", "ota", "ista",
            "ialista", "ionista", "iva", "ativa", "nça", "ístic", "enc", "esc",
            "ud", "atge", "ble", "able", "ible", "isme", "ialisme", "ionisme",
            "ivisme", "aire", "icte", "iste", "ici", "íci", "ari", "tori",
            "al", "il", "all", "ell", "ívol", "isam", "issem", "ìssem",
            "íssem", "íssim", "amen", "ìssin", "ar", "ificar", "egar", "ejar",
            "itar", "itzar", "fer", "or", "dor", "dur", "doras", "uds",
            "nces", "ancies", "encies", "ències", "ícies", "inies", "ínies", "eries",
            "àries", "atòries", "bles", "ables", "ibles", "imes", "íssimes", "formes",
            "ismes", "ialismes", "ines", "eres", "ores", "dores", "idores", "dures",
            "eses", "oses", "asses", "ictes", "ites", "otes", "istes", "ialistes",
            "ionistes", "ives", "atives", "allengües", "icis", "ícis", "aris", "toris",
            "ls", "als", "ells", "ims", "íssims", "ions", "cions", "esos",
            "osos", "assos", "issos", "ers", "ors", "dors", "adors", "idors",
            "ats", "itats", "bilitats", "ivitats", "ativitats", "ïtats", "ets", "ants",
            "ents", "ments", "aments", "ots", "uts", "ius", "trius", "atius",
            "ès", "és", "ís", "dís", "ós", "itat", "bilitat", "ivitat",
            "ativitat", "ïtat", "et", "ant", "ent", "ient", "ment", "ament",
            "isament", "ot", "isseu", "ìsseu", "ísseu", "triu", "íssiu", "atiu",
            "ó", "ió", "ció", "ació"),
        g("ada", "ades", "acions"),
        g("lógica", "logia", "logía", "logi", "lógics", "logies", "lógiques", "logíes", "logis"),
        g("ica", "ic", "ics", "iques"),
        g("quíssima", "quíssim", "quíssimes", "quíssims"));

    private static final Among VERB = Among.of(
        g(
            "aba", "esca", "isca", "ïsca", "ada", "ida", "uda", "ïda",
            "ia", "aria", "iria", "ara", "iera", "ira", "adora", "ïra",
            "ava", "ixa", "itza", "ía", "aría", "ería", "iría", "ïa",
            "isc", "ïsc", "ad", "ed", "id", "ie", "re", "dre",
            "ase", "iese", "aste", "iste", "ii", "ini", "esqui", "eixi",
            "itzi", "am", "em", "arem", "irem", "àrem", "írem", "àssem",
            "éssem", "iguem", "ïguem", "avem", "àvem", "ávem", "irìem", "íem",
            "aríem", "iríem", "assim", "essim", "issim", "àssim", "èssim", "éssim",
            "íssim", "ïm", "an", "aban", "arian", "aran", "ieran", "iran",
            "ían", "arían", "erían", "irían", "en", "ien", "arien", "irien",
            "aren", "eren", "iren", "àren", "ïren", "asen", "iesen", "assen",
            "essen", "issen", "éssen", "ïssen", "esquen", "isquen", "ïsquen", "aven",
            "ixen", "eixen", "ïxen", "ïen", "in", "inin", "sin", "isin",
            "assin", "essin", "issin", "ïssin", "esquin", "eixin", "aron", "ieron",
            "arán", "erán", "irán", "iïn", "ado", "ido", "iendo", "io",
            "ixo", "eixo", "ïxo", "itzo", "ar", "tzar", "er", "eixer",
            "ir", "ador", "as", "abas", "adas", "idas", "aras", "ieras",
            "ías", "arías", "erías", "irías", "ids", "es", "ades", "ides",
            "udes", "ïdes", "atges", "ies", "aries", "iries", "ares", "ires",
            "adores", "ïres", "ases", "ieses", "asses", "esses", "isses", "ïsses",
            "ques", "esques", "ïsques", "aves", "ixes", "eixes", "ïxes", "ïes",
            "abais", "arais", "ierais", "íais", "aríais", "eríais", "iríais", "aseis",
            "ieseis", "asteis", "isteis", "inis", "sis", "isis", "assis", "essis",
            "issis", "ïssis", "esquis", "eixis", "itzis", "áis", "aréis", "eréis",
            "iréis", "ams", "ados", "idos", "amos", "ábamos", "áramos", "iéramos",
            "íamos", "aríamos", "eríamos", "iríamos", "aremos", "eremos", "iremos", "ásemos",
            "iésemos", "imos", "adors", "ass", "erass", "ess", "ats", "its",
            "ents", "às", "aràs", "iràs", "arás", "erás", "irás", "és",
            "arés", "ís", "iïs", "at", "it", "ant", "ent", "int",
            "ut", "ït", "au", "erau", "ieu", "ineu", "areu", "ireu",
            "àreu", "íreu", "asseu", "esseu", "eresseu", "àsseu", "ésseu", "igueu",
            "ïgueu", "àveu", "áveu", "itzeu", "ìeu", "irìeu", "íeu", "aríeu",
            "iríeu", "assiu", "issiu", "àssiu", "èssiu", "éssiu", "íssiu", "ïu",
            "ix", "eix", "ïx", "itz", "ià", "arà", "irà", "itzà",
            "ará", "erá", "irá", "irè", "aré", "eré", "iré", "í",
            "iï", "ió"),
        g("ando"));

    private static final Among RESIDUAL = Among.of(
        g("a", "e", "i", "ïn", "o", "ir", "s", "is", "os", "ïs", "it", "eu", "iu", "itz",
            "à", "á", "é", "ì", "í", "ï", "ó"),
        g("iqu"));

    private int p2;
    private int p1;

    public CatalanSnowballStemmer() {
    }

    @Override
    protected void run() {
        markRegions();
        limitBackward = cursor;
        cursor = limit;
        int v1 = limit - cursor;
        attachedPronoun();
        cursor = limit - v1;
        int v2 = limit - cursor;
        if (!standardSuffix()) {
            cursor = limit - v2;
            verbSuffix();
        }
        cursor = limit - v2;
        int v4 = limit - cursor;
        residualSuffix();
        cursor = limit - v4;
        cursor = limitBackward;
        int v5 = cursor;
        cleaning();
        cursor = v5;
    }

    private void markRegions() {
        p1 = limit;
        p2 = limit;
        int c = cursor;
        if (goPastIn(V) && goPastOut(V)) {
            p1 = cursor;
            if (goPastIn(V) && goPastOut(V)) {
                p2 = cursor;
            }
        }
        cursor = c;
    }

    private boolean r1() {
        return p1 <= cursor;
    }

    private boolean r2() {
        return p2 <= cursor;
    }

    private void cleaning() {
        while (true) {
            int c = cursor;
            bra = cursor;
            int a = findAmong(CLEANING);
            ket = cursor;
            switch (a) {
                case 1 -> sliceFrom("a");
                case 2 -> sliceFrom("e");
                case 3 -> sliceFrom("i");
                case 4 -> sliceFrom("o");
                case 5 -> sliceFrom("u");
                case 6 -> sliceFrom(".");
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

    private boolean attachedPronoun() {
        ket = cursor;
        if (findAmongB(ATTACHED_PRONOUN) == 0) {
            return false;
        }
        bra = cursor;
        if (!r1()) {
            return false;
        }
        sliceDel();
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
                sliceFrom("ic");
            }
            case 5 -> {
                if (!r1()) {
                    return false;
                }
                sliceFrom("c");
            }
            default -> {
            }
        }
        return true;
    }

    private boolean verbSuffix() {
        ket = cursor;
        int a = findAmongB(VERB);
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

    private boolean residualSuffix() {
        ket = cursor;
        int a = findAmongB(RESIDUAL);
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
                if (!r1()) {
                    return false;
                }
                sliceFrom("ic");
            }
            default -> {
            }
        }
        return true;
    }
}
