package com.naqqa.elasticsearch.analysis.lang.a;

import com.naqqa.elasticsearch.analysis.stem.snowball.SnowballSupport;

public final class BasqueSnowballStemmer extends SnowballSupport {

    private static final String V = "aeiou";

    private static final Among ADITZAK = Among.of(
        g(
            "idea", "bidea", "kidea", "pidea", "kundea", "galea", "tailea", "tzailea", "gunea", "kunea",
            "tzaga", "gaia", "aldia", "taldia", "karia", "karria", "ka", "tzaka", "la", "mena", "pena",
            "kina", "ezina", "tezina", "kuna", "tuna", "kizuna", "era", "bera", "kera", "pera", "orra",
            "korra", "dura", "gura", "kura", "tura", "eta", "keta", "gailua", "eza", "erreza", "gaitza",
            "kaitza", "kuntza", "ide", "bide", "kide", "pide", "kunde", "tzake", "tzeke", "le", "gale",
            "taile", "tzaile", "gune", "kune", "tze", "atze", "gai", "aldi", "taldi", "ki", "ari", "kari",
            "lari", "tari", "etari", "karri", "arazi", "tarazi", "an", "ean", "rean", "kan", "etan", "men",
            "pen", "kin", "rekin", "ezin", "tezin", "tun", "kizun", "go", "ago", "tio", "dako", "or",
            "kor", "tzat", "du", "gailu", "tu", "atu", "aldatu", "tatu", "ez", "errez", "tzez", "gaitz",
            "kaitz"),
        g("garria", "tza", "garri"),
        g("arabera", "atseden", "baditu"));

    private static final Among IZENAK = Among.of(
        g(
            "ada", "kada", "anda", "denda", "gabea", "kabea", "aldea", "kaldea", "taldea", "ordea",
            "zalea", "tzalea", "gilea", "emea", "kumea", "nea", "enea", "zionea", "unea", "gunea",
            "pea", "aurrea", "tea", "kotea", "artea", "ostea", "etxea", "ga", "anga", "gaia",
            "aldia", "taldia", "handia", "mendia", "geia", "egia", "degia", "tegia", "nahia", "ohia",
            "kia", "tokia", "oia", "koia", "aria", "karia", "laria", "taria", "eria", "keria",
            "teria", "larria", "kirria", "duria", "asia", "tia", "ezia", "bizia", "ontzia", "ka",
            "ska", "xka", "zka", "gibela", "gela", "kaila", "skila", "tila", "ola", "na",
            "kana", "ena", "garrena", "gerrena", "urrena", "zaina", "tzaina", "kina", "mina", "garna",
            "una", "duna", "asuna", "tasuna", "ondoa", "kondoa", "ngoa", "zioa", "koa", "takoa",
            "zkoa", "noa", "zinoa", "aroa", "taroa", "zaroa", "eroa", "oroa", "osoa", "toa",
            "ttoa", "ztoa", "txoa", "tzoa", "ra", "ara", "dara", "liara", "tiara", "tara",
            "etara", "tzara", "bera", "kera", "pera", "tzarra", "korra", "tra", "sa", "osa",
            "ta", "eta", "keta", "sta", "dua", "mendua", "ordua", "lekua", "burua", "durua",
            "tsua", "tua", "mentua", "estua", "txua", "zua", "tzua", "za", "eza", "eroza",
            "koitza", "antza", "gintza", "kintza", "kuntza", "gabe", "kabe", "kide", "alde", "kalde",
            "talde", "orde", "ge", "zale", "tzale", "gile", "eme", "kume", "ne", "zione",
            "une", "gune", "pe", "aurre", "te", "kote", "arte", "oste", "etxe", "gai",
            "di", "aldi", "taldi", "handi", "mendi", "gei", "egi", "degi", "tegi", "nahi",
            "ohi", "ki", "toki", "oi", "goi", "koi", "ari", "kari", "lari", "tari",
            "larri", "kirri", "duri", "asi", "ti", "ontzi", "ak", "ek", "tarik", "gibel",
            "ail", "kail", "kan", "tan", "etan", "garren", "gerren", "urren", "zain", "tzain",
            "kin", "min", "dun", "asun", "tasun", "aizun", "ondo", "kondo", "go", "ngo",
            "zio", "ko", "tako", "etako", "eko", "tariko", "sko", "tuko", "zko", "no",
            "zino", "ro", "aro", "taro", "zaro", "ero", "giro", "oro", "oso", "to",
            "tto", "zto", "txo", "tzo", "gintzo", "zp", "ar", "dar", "behar", "liar",
            "tiar", "tar", "tzar", "kor", "os", "ket", "du", "mendu", "ordu", "leku",
            "duru", "tsu", "tu", "mentu", "estu", "txu", "zu", "tzu", "gintzu", "z",
            "ez", "eroz", "tz", "koitz", "ñoa", "ñi", "ño"),
        g("garria", "ora", "tza", "garri", "ren", "or", "buru"),
        g("joka"),
        g("en", "ten", "tzen", "tatu"),
        g("trako"),
        g("minutuko"),
        g("aurka", "geldi", "igaro", "zehar"));

    private static final Among ADJETIBOAK = Among.of(
        g("keria", "la", "era", "dade", "tade", "date", "tate", "gi", "ki", "ik", "lanik", "rik", "larik", "ztik", "go", "ro", "ero", "to"),
        g("zlea"));

    private int p2;
    private int p1;
    private int pV;

    public BasqueSnowballStemmer() {
    }

    @Override
    protected void run() {
        markRegions();
        limitBackward = cursor;
        cursor = limit;
        while (aditzak()) {
        }
        while (izenak()) {
        }
        int v = limit - cursor;
        adjetiboak();
        cursor = limit - v;
        cursor = limitBackward;
    }

    private void markRegions() {
        pV = limit;
        p1 = limit;
        p2 = limit;
        int c = cursor;
        boolean found = false;
        int v2 = cursor;
        if (inGrouping(V)) {
            int v3 = cursor;
            if (outGrouping(V) && goPastIn(V)) {
                found = true;
            } else {
                cursor = v3;
                if (inGrouping(V) && goPastOut(V)) {
                    found = true;
                }
            }
        }
        if (!found) {
            cursor = v2;
            if (outGrouping(V)) {
                int v4 = cursor;
                if (outGrouping(V) && goPastIn(V)) {
                    found = true;
                } else {
                    cursor = v4;
                    if (inGrouping(V) && cursor < limit) {
                        cursor++;
                        found = true;
                    }
                }
            }
        }
        if (found) {
            pV = cursor;
        }
        cursor = c;
        int v5 = cursor;
        if (goPastIn(V) && goPastOut(V)) {
            p1 = cursor;
            if (goPastIn(V) && goPastOut(V)) {
                p2 = cursor;
            }
        }
        cursor = v5;
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

    private boolean aditzak() {
        int save = limit - cursor;
        ket = cursor;
        int a = findAmongB(ADITZAK);
        if (a == 0) {
            cursor = limit - save;
            return false;
        }
        bra = cursor;
        boolean ok;
        switch (a) {
            case 1 -> {
                if (rv()) {
                    sliceDel();
                    ok = true;
                } else {
                    ok = false;
                }
            }
            case 2 -> {
                if (r2()) {
                    sliceDel();
                    ok = true;
                } else {
                    ok = false;
                }
            }
            default -> ok = true;
        }
        if (!ok) {
            cursor = limit - save;
            return false;
        }
        return true;
    }

    private boolean izenak() {
        int save = limit - cursor;
        ket = cursor;
        int a = findAmongB(IZENAK);
        if (a == 0) {
            cursor = limit - save;
            return false;
        }
        bra = cursor;
        boolean ok = true;
        switch (a) {
            case 1 -> {
                if (rv()) {
                    sliceDel();
                } else {
                    ok = false;
                }
            }
            case 2 -> {
                if (r2()) {
                    sliceDel();
                } else {
                    ok = false;
                }
            }
            case 3 -> sliceFrom("jok");
            case 4 -> {
                if (r1()) {
                    sliceDel();
                } else {
                    ok = false;
                }
            }
            case 5 -> sliceFrom("tra");
            case 6 -> sliceFrom("minutu");
            default -> {
            }
        }
        if (!ok) {
            cursor = limit - save;
            return false;
        }
        return true;
    }

    private boolean adjetiboak() {
        ket = cursor;
        int a = findAmongB(ADJETIBOAK);
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
            case 2 -> sliceFrom("z");
            default -> {
            }
        }
        return true;
    }
}
