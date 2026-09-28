package com.naqqa.elasticsearch.analysis.lang.a;

import com.naqqa.elasticsearch.analysis.stem.snowball.SnowballSupport;

public final class TurkishSnowballStemmer extends SnowballSupport {

    private static final String V = "aeiouöüı";
    private static final String U = "iuüı";
    private static final String V1 = "aoı" + "u";
    private static final String V2 = "eiöü";
    private static final String V3 = "aı";
    private static final String V4 = "ei";
    private static final String V5 = "ou";
    private static final String V6 = "öü";

    private static final Among POSSESSIVES = Among.of(g(
        "m", "n", "miz", "niz", "muz", "nuz", "müz", "nüz", "mız", "nız"));
    private static final Among LARI = Among.of(g("leri", "ları"));
    private static final Among NU = Among.of(g("ni", "nu", "nü", "nı"));
    private static final Among NUN = Among.of(g("in", "un", "ün", "ın"));
    private static final Among YA = Among.of(g("a", "e"));
    private static final Among NA = Among.of(g("na", "ne"));
    private static final Among DA = Among.of(g("da", "ta", "de", "te"));
    private static final Among NDA = Among.of(g("nda", "nde"));
    private static final Among DAN = Among.of(g("dan", "tan", "den", "ten"));
    private static final Among NDAN = Among.of(g("ndan", "nden"));
    private static final Among YLA = Among.of(g("la", "le"));
    private static final Among NCA = Among.of(g("ca", "ce"));
    private static final Among YUM = Among.of(g("im", "um", "üm", "ım"));
    private static final Among SUN = Among.of(g("sin", "sun", "sün", "sın"));
    private static final Among YUZ = Among.of(g("iz", "uz", "üz", "ız"));
    private static final Among SUNUZ = Among.of(g("siniz", "sunuz", "sünüz", "sınız"));
    private static final Among LAR = Among.of(g("lar", "ler"));
    private static final Among NUZ = Among.of(g("niz", "nuz", "nüz", "nız"));
    private static final Among DUR = Among.of(g("dir", "tir", "dur", "tur", "dür", "tür", "dır", "tır"));
    private static final Among CASINA = Among.of(g("casına", "cesine"));
    private static final Among YDU = Among.of(g(
        "di", "ti", "dik", "tik", "duk", "tuk", "dük", "tük", "dık", "tık",
        "dim", "tim", "dum", "tum", "düm", "tüm", "dım", "tım",
        "din", "tin", "dun", "tun", "dün", "tün", "dın", "tın",
        "du", "tu", "dü", "tü", "dı", "tı"));
    private static final Among YSA = Among.of(g("sa", "se", "sak", "sek", "sam", "sem", "san", "sen"));
    private static final Among YMUS = Among.of(g("miş", "muş", "müş", "mış"));
    private static final Among LAST_CONSONANTS = Among.of(g("b"), g("c"), g("d"), g("ğ"));

    private boolean continueStemmingNounSuffixes;

    public TurkishSnowballStemmer() {
    }

    @Override
    protected void run() {
        removeProperNounSuffix();
        if (!moreThanOneSyllableWord()) {
            return;
        }
        limitBackward = cursor;
        cursor = limit;
        int v1 = limit - cursor;
        stemNominalVerbSuffixes();
        cursor = limit - v1;
        if (!continueStemmingNounSuffixes) {
            return;
        }
        int v2 = limit - cursor;
        stemNounSuffixes();
        cursor = limit - v2;
        cursor = limitBackward;
        postlude();
    }

    private boolean goOutGroupingB(String grouping) {
        while (cursor > limitBackward) {
            if (in(grouping, current.charAt(cursor - 1))) {
                return true;
            }
            cursor--;
        }
        return false;
    }

    private boolean checkVowelHarmony() {
        int v1 = limit - cursor;
        if (!goOutGroupingB(V)) {
            return false;
        }
        lab0:
        {
            int v2 = limit - cursor;
            lab1:
            {
                if (!eqSB("a")) {
                    break lab1;
                }
                if (!goOutGroupingB(V1)) {
                    break lab1;
                }
                break lab0;
            }
            cursor = limit - v2;
            lab2:
            {
                if (!eqSB("e")) {
                    break lab2;
                }
                if (!goOutGroupingB(V2)) {
                    break lab2;
                }
                break lab0;
            }
            cursor = limit - v2;
            lab3:
            {
                if (!eqSB("ı")) {
                    break lab3;
                }
                if (!goOutGroupingB(V3)) {
                    break lab3;
                }
                break lab0;
            }
            cursor = limit - v2;
            lab4:
            {
                if (!eqSB("i")) {
                    break lab4;
                }
                if (!goOutGroupingB(V4)) {
                    break lab4;
                }
                break lab0;
            }
            cursor = limit - v2;
            lab5:
            {
                if (!eqSB("o")) {
                    break lab5;
                }
                if (!goOutGroupingB(V5)) {
                    break lab5;
                }
                break lab0;
            }
            cursor = limit - v2;
            lab6:
            {
                if (!eqSB("ö")) {
                    break lab6;
                }
                if (!goOutGroupingB(V6)) {
                    break lab6;
                }
                break lab0;
            }
            cursor = limit - v2;
            lab7:
            {
                if (!eqSB("u")) {
                    break lab7;
                }
                if (!goOutGroupingB(V5)) {
                    break lab7;
                }
                break lab0;
            }
            cursor = limit - v2;
            if (!eqSB("ü")) {
                return false;
            }
            if (!goOutGroupingB(V6)) {
                return false;
            }
        }
        cursor = limit - v1;
        return true;
    }

    private boolean markSuffixOptionalConsonant(String consonant) {
        lab0:
        {
            int v1 = limit - cursor;
            lab1:
            {
                if (!eqSB(consonant)) {
                    break lab1;
                }
                int v2 = limit - cursor;
                if (!inGroupingB(V)) {
                    break lab1;
                }
                cursor = limit - v2;
                break lab0;
            }
            cursor = limit - v1;
            {
                int v3 = limit - cursor;
                lab2:
                {
                    if (!eqSB(consonant)) {
                        break lab2;
                    }
                    return false;
                }
                cursor = limit - v3;
            }
            int v4 = limit - cursor;
            if (cursor <= limitBackward) {
                return false;
            }
            cursor--;
            if (!inGroupingB(V)) {
                return false;
            }
            cursor = limit - v4;
        }
        return true;
    }

    private boolean markSuffixOptionalUVowel() {
        lab0:
        {
            int v1 = limit - cursor;
            lab1:
            {
                if (!inGroupingB(U)) {
                    break lab1;
                }
                int v2 = limit - cursor;
                if (!outGroupingB(V)) {
                    break lab1;
                }
                cursor = limit - v2;
                break lab0;
            }
            cursor = limit - v1;
            {
                int v3 = limit - cursor;
                lab2:
                {
                    if (!inGroupingB(U)) {
                        break lab2;
                    }
                    return false;
                }
                cursor = limit - v3;
            }
            int v4 = limit - cursor;
            if (cursor <= limitBackward) {
                return false;
            }
            cursor--;
            if (!outGroupingB(V)) {
                return false;
            }
            cursor = limit - v4;
        }
        return true;
    }

    private boolean markPossessives() {
        if (findAmongB(POSSESSIVES) == 0) {
            return false;
        }
        return markSuffixOptionalUVowel();
    }

    private boolean markSU() {
        if (!checkVowelHarmony()) {
            return false;
        }
        if (!inGroupingB(U)) {
            return false;
        }
        return markSuffixOptionalConsonant("s");
    }

    private boolean markLArI() {
        return findAmongB(LARI) != 0;
    }

    private boolean markYU() {
        if (!checkVowelHarmony()) {
            return false;
        }
        if (!inGroupingB(U)) {
            return false;
        }
        return markSuffixOptionalConsonant("y");
    }

    private boolean markNU() {
        if (!checkVowelHarmony()) {
            return false;
        }
        return findAmongB(NU) != 0;
    }

    private boolean markNUn() {
        if (!checkVowelHarmony()) {
            return false;
        }
        if (findAmongB(NUN) == 0) {
            return false;
        }
        return markSuffixOptionalConsonant("n");
    }

    private boolean markYA() {
        if (!checkVowelHarmony()) {
            return false;
        }
        if (findAmongB(YA) == 0) {
            return false;
        }
        return markSuffixOptionalConsonant("y");
    }

    private boolean markNA() {
        if (!checkVowelHarmony()) {
            return false;
        }
        return findAmongB(NA) != 0;
    }

    private boolean markDA() {
        if (!checkVowelHarmony()) {
            return false;
        }
        return findAmongB(DA) != 0;
    }

    private boolean markNdA() {
        if (!checkVowelHarmony()) {
            return false;
        }
        return findAmongB(NDA) != 0;
    }

    private boolean markDAn() {
        if (!checkVowelHarmony()) {
            return false;
        }
        return findAmongB(DAN) != 0;
    }

    private boolean markNdAn() {
        if (!checkVowelHarmony()) {
            return false;
        }
        return findAmongB(NDAN) != 0;
    }

    private boolean markYlA() {
        if (!checkVowelHarmony()) {
            return false;
        }
        if (findAmongB(YLA) == 0) {
            return false;
        }
        return markSuffixOptionalConsonant("y");
    }

    private boolean markKi() {
        return eqSB("ki");
    }

    private boolean markNcA() {
        if (!checkVowelHarmony()) {
            return false;
        }
        if (findAmongB(NCA) == 0) {
            return false;
        }
        return markSuffixOptionalConsonant("n");
    }

    private boolean markYUm() {
        if (!checkVowelHarmony()) {
            return false;
        }
        if (findAmongB(YUM) == 0) {
            return false;
        }
        return markSuffixOptionalConsonant("y");
    }

    private boolean markSUn() {
        if (!checkVowelHarmony()) {
            return false;
        }
        return findAmongB(SUN) != 0;
    }

    private boolean markYUz() {
        if (!checkVowelHarmony()) {
            return false;
        }
        if (findAmongB(YUZ) == 0) {
            return false;
        }
        return markSuffixOptionalConsonant("y");
    }

    private boolean markSUnUz() {
        return findAmongB(SUNUZ) != 0;
    }

    private boolean markLAr() {
        if (!checkVowelHarmony()) {
            return false;
        }
        return findAmongB(LAR) != 0;
    }

    private boolean markNUz() {
        if (!checkVowelHarmony()) {
            return false;
        }
        return findAmongB(NUZ) != 0;
    }

    private boolean markDUr() {
        if (!checkVowelHarmony()) {
            return false;
        }
        return findAmongB(DUR) != 0;
    }

    private boolean markCAsInA() {
        return findAmongB(CASINA) != 0;
    }

    private boolean markYDU() {
        if (!checkVowelHarmony()) {
            return false;
        }
        if (findAmongB(YDU) == 0) {
            return false;
        }
        return markSuffixOptionalConsonant("y");
    }

    private boolean markYsA() {
        if (findAmongB(YSA) == 0) {
            return false;
        }
        return markSuffixOptionalConsonant("y");
    }

    private boolean markYmUs() {
        if (!checkVowelHarmony()) {
            return false;
        }
        if (findAmongB(YMUS) == 0) {
            return false;
        }
        return markSuffixOptionalConsonant("y");
    }

    private boolean markYken() {
        if (!eqSB("ken")) {
            return false;
        }
        return markSuffixOptionalConsonant("y");
    }

    private boolean stemNominalVerbSuffixes() {
        ket = cursor;
        continueStemmingNounSuffixes = true;
        lab0:
        {
            int v1 = limit - cursor;
            lab1:
            {
                lab2:
                {
                    int v2 = limit - cursor;
                    lab3:
                    {
                        if (!markYmUs()) {
                            break lab3;
                        }
                        break lab2;
                    }
                    cursor = limit - v2;
                    lab4:
                    {
                        if (!markYDU()) {
                            break lab4;
                        }
                        break lab2;
                    }
                    cursor = limit - v2;
                    lab5:
                    {
                        if (!markYsA()) {
                            break lab5;
                        }
                        break lab2;
                    }
                    cursor = limit - v2;
                    if (!markYken()) {
                        break lab1;
                    }
                }
                break lab0;
            }
            cursor = limit - v1;
            lab6:
            {
                if (!markCAsInA()) {
                    break lab6;
                }
                lab7:
                {
                    int v3 = limit - cursor;
                    lab8:
                    {
                        if (!markSUnUz()) {
                            break lab8;
                        }
                        break lab7;
                    }
                    cursor = limit - v3;
                    lab9:
                    {
                        if (!markLAr()) {
                            break lab9;
                        }
                        break lab7;
                    }
                    cursor = limit - v3;
                    lab10:
                    {
                        if (!markYUm()) {
                            break lab10;
                        }
                        break lab7;
                    }
                    cursor = limit - v3;
                    lab11:
                    {
                        if (!markSUn()) {
                            break lab11;
                        }
                        break lab7;
                    }
                    cursor = limit - v3;
                    lab12:
                    {
                        if (!markYUz()) {
                            break lab12;
                        }
                        break lab7;
                    }
                    cursor = limit - v3;
                }
                if (!markYmUs()) {
                    break lab6;
                }
                break lab0;
            }
            cursor = limit - v1;
            lab13:
            {
                if (!markLAr()) {
                    break lab13;
                }
                bra = cursor;
                sliceDel();
                int v4 = limit - cursor;
                lab14:
                {
                    ket = cursor;
                    lab15:
                    {
                        int v5 = limit - cursor;
                        lab16:
                        {
                            if (!markDUr()) {
                                break lab16;
                            }
                            break lab15;
                        }
                        cursor = limit - v5;
                        lab17:
                        {
                            if (!markYDU()) {
                                break lab17;
                            }
                            break lab15;
                        }
                        cursor = limit - v5;
                        lab18:
                        {
                            if (!markYsA()) {
                                break lab18;
                            }
                            break lab15;
                        }
                        cursor = limit - v5;
                        if (!markYmUs()) {
                            cursor = limit - v4;
                            break lab14;
                        }
                    }
                }
                continueStemmingNounSuffixes = false;
                break lab0;
            }
            cursor = limit - v1;
            lab19:
            {
                if (!markNUz()) {
                    break lab19;
                }
                lab20:
                {
                    int v6 = limit - cursor;
                    lab21:
                    {
                        if (!markYDU()) {
                            break lab21;
                        }
                        break lab20;
                    }
                    cursor = limit - v6;
                    if (!markYsA()) {
                        break lab19;
                    }
                }
                break lab0;
            }
            cursor = limit - v1;
            lab22:
            {
                lab23:
                {
                    int v7 = limit - cursor;
                    lab24:
                    {
                        if (!markSUnUz()) {
                            break lab24;
                        }
                        break lab23;
                    }
                    cursor = limit - v7;
                    lab25:
                    {
                        if (!markYUz()) {
                            break lab25;
                        }
                        break lab23;
                    }
                    cursor = limit - v7;
                    lab26:
                    {
                        if (!markSUn()) {
                            break lab26;
                        }
                        break lab23;
                    }
                    cursor = limit - v7;
                    if (!markYUm()) {
                        break lab22;
                    }
                }
                bra = cursor;
                sliceDel();
                int v8 = limit - cursor;
                lab27:
                {
                    ket = cursor;
                    if (!markYmUs()) {
                        cursor = limit - v8;
                        break lab27;
                    }
                }
                break lab0;
            }
            cursor = limit - v1;
            if (!markDUr()) {
                return false;
            }
            bra = cursor;
            sliceDel();
            int v9 = limit - cursor;
            lab28:
            {
                ket = cursor;
                lab29:
                {
                    int v10 = limit - cursor;
                    lab30:
                    {
                        if (!markSUnUz()) {
                            break lab30;
                        }
                        break lab29;
                    }
                    cursor = limit - v10;
                    lab31:
                    {
                        if (!markLAr()) {
                            break lab31;
                        }
                        break lab29;
                    }
                    cursor = limit - v10;
                    lab32:
                    {
                        if (!markYUm()) {
                            break lab32;
                        }
                        break lab29;
                    }
                    cursor = limit - v10;
                    lab33:
                    {
                        if (!markSUn()) {
                            break lab33;
                        }
                        break lab29;
                    }
                    cursor = limit - v10;
                    lab34:
                    {
                        if (!markYUz()) {
                            break lab34;
                        }
                        break lab29;
                    }
                    cursor = limit - v10;
                }
                if (!markYmUs()) {
                    cursor = limit - v9;
                    break lab28;
                }
            }
        }
        bra = cursor;
        sliceDel();
        return true;
    }

    private boolean stemSuffixChainBeforeKi() {
        ket = cursor;
        if (!markKi()) {
            return false;
        }
        lab0:
        {
            int v1 = limit - cursor;
            lab1:
            {
                if (!markDA()) {
                    break lab1;
                }
                bra = cursor;
                sliceDel();
                int v2 = limit - cursor;
                lab2:
                {
                    ket = cursor;
                    lab3:
                    {
                        int v3 = limit - cursor;
                        lab4:
                        {
                            if (!markLAr()) {
                                break lab4;
                            }
                            bra = cursor;
                            sliceDel();
                            int v4 = limit - cursor;
                            lab5:
                            {
                                if (!stemSuffixChainBeforeKi()) {
                                    cursor = limit - v4;
                                    break lab5;
                                }
                            }
                            break lab3;
                        }
                        cursor = limit - v3;
                        if (!markPossessives()) {
                            cursor = limit - v2;
                            break lab2;
                        }
                        bra = cursor;
                        sliceDel();
                        int v5 = limit - cursor;
                        lab6:
                        {
                            ket = cursor;
                            if (!markLAr()) {
                                cursor = limit - v5;
                                break lab6;
                            }
                            bra = cursor;
                            sliceDel();
                            if (!stemSuffixChainBeforeKi()) {
                                cursor = limit - v5;
                                break lab6;
                            }
                        }
                    }
                }
                break lab0;
            }
            cursor = limit - v1;
            lab7:
            {
                if (!markNUn()) {
                    break lab7;
                }
                bra = cursor;
                sliceDel();
                int v6 = limit - cursor;
                lab8:
                {
                    ket = cursor;
                    lab9:
                    {
                        int v7 = limit - cursor;
                        lab10:
                        {
                            if (!markLArI()) {
                                break lab10;
                            }
                            bra = cursor;
                            sliceDel();
                            break lab9;
                        }
                        cursor = limit - v7;
                        lab11:
                        {
                            ket = cursor;
                            lab12:
                            {
                                int v8 = limit - cursor;
                                lab13:
                                {
                                    if (!markPossessives()) {
                                        break lab13;
                                    }
                                    break lab12;
                                }
                                cursor = limit - v8;
                                if (!markSU()) {
                                    break lab11;
                                }
                            }
                            bra = cursor;
                            sliceDel();
                            int v9 = limit - cursor;
                            lab14:
                            {
                                ket = cursor;
                                if (!markLAr()) {
                                    cursor = limit - v9;
                                    break lab14;
                                }
                                bra = cursor;
                                sliceDel();
                                if (!stemSuffixChainBeforeKi()) {
                                    cursor = limit - v9;
                                    break lab14;
                                }
                            }
                            break lab9;
                        }
                        cursor = limit - v7;
                        if (!stemSuffixChainBeforeKi()) {
                            cursor = limit - v6;
                            break lab8;
                        }
                    }
                }
                break lab0;
            }
            cursor = limit - v1;
            if (!markNdA()) {
                return false;
            }
            lab15:
            {
                int v10 = limit - cursor;
                lab16:
                {
                    if (!markLArI()) {
                        break lab16;
                    }
                    bra = cursor;
                    sliceDel();
                    break lab15;
                }
                cursor = limit - v10;
                lab17:
                {
                    if (!markSU()) {
                        break lab17;
                    }
                    bra = cursor;
                    sliceDel();
                    int v11 = limit - cursor;
                    lab18:
                    {
                        ket = cursor;
                        if (!markLAr()) {
                            cursor = limit - v11;
                            break lab18;
                        }
                        bra = cursor;
                        sliceDel();
                        if (!stemSuffixChainBeforeKi()) {
                            cursor = limit - v11;
                            break lab18;
                        }
                    }
                    break lab15;
                }
                cursor = limit - v10;
                if (!stemSuffixChainBeforeKi()) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean stemNounSuffixes() {
        lab0:
        {
            int v1 = limit - cursor;
            lab1:
            {
                ket = cursor;
                if (!markLAr()) {
                    break lab1;
                }
                bra = cursor;
                sliceDel();
                int v2 = limit - cursor;
                lab2:
                {
                    if (!stemSuffixChainBeforeKi()) {
                        cursor = limit - v2;
                        break lab2;
                    }
                }
                break lab0;
            }
            cursor = limit - v1;
            lab3:
            {
                ket = cursor;
                if (!markNcA()) {
                    break lab3;
                }
                bra = cursor;
                sliceDel();
                int v3 = limit - cursor;
                lab4:
                {
                    lab5:
                    {
                        int v4 = limit - cursor;
                        lab6:
                        {
                            ket = cursor;
                            if (!markLArI()) {
                                break lab6;
                            }
                            bra = cursor;
                            sliceDel();
                            break lab5;
                        }
                        cursor = limit - v4;
                        lab7:
                        {
                            ket = cursor;
                            lab8:
                            {
                                int v5 = limit - cursor;
                                lab9:
                                {
                                    if (!markPossessives()) {
                                        break lab9;
                                    }
                                    break lab8;
                                }
                                cursor = limit - v5;
                                if (!markSU()) {
                                    break lab7;
                                }
                            }
                            bra = cursor;
                            sliceDel();
                            int v6 = limit - cursor;
                            lab10:
                            {
                                ket = cursor;
                                if (!markLAr()) {
                                    cursor = limit - v6;
                                    break lab10;
                                }
                                bra = cursor;
                                sliceDel();
                                if (!stemSuffixChainBeforeKi()) {
                                    cursor = limit - v6;
                                    break lab10;
                                }
                            }
                            break lab5;
                        }
                        cursor = limit - v4;
                        ket = cursor;
                        if (!markLAr()) {
                            cursor = limit - v3;
                            break lab4;
                        }
                        bra = cursor;
                        sliceDel();
                        if (!stemSuffixChainBeforeKi()) {
                            cursor = limit - v3;
                            break lab4;
                        }
                    }
                }
                break lab0;
            }
            cursor = limit - v1;
            lab11:
            {
                ket = cursor;
                lab12:
                {
                    int v7 = limit - cursor;
                    lab13:
                    {
                        if (!markNdA()) {
                            break lab13;
                        }
                        break lab12;
                    }
                    cursor = limit - v7;
                    if (!markNA()) {
                        break lab11;
                    }
                }
                lab14:
                {
                    int v8 = limit - cursor;
                    lab15:
                    {
                        if (!markLArI()) {
                            break lab15;
                        }
                        bra = cursor;
                        sliceDel();
                        break lab14;
                    }
                    cursor = limit - v8;
                    lab16:
                    {
                        if (!markSU()) {
                            break lab16;
                        }
                        bra = cursor;
                        sliceDel();
                        int v9 = limit - cursor;
                        lab17:
                        {
                            ket = cursor;
                            if (!markLAr()) {
                                cursor = limit - v9;
                                break lab17;
                            }
                            bra = cursor;
                            sliceDel();
                            if (!stemSuffixChainBeforeKi()) {
                                cursor = limit - v9;
                                break lab17;
                            }
                        }
                        break lab14;
                    }
                    cursor = limit - v8;
                    if (!stemSuffixChainBeforeKi()) {
                        break lab11;
                    }
                }
                break lab0;
            }
            cursor = limit - v1;
            lab18:
            {
                ket = cursor;
                lab19:
                {
                    int v10 = limit - cursor;
                    lab20:
                    {
                        if (!markNdAn()) {
                            break lab20;
                        }
                        break lab19;
                    }
                    cursor = limit - v10;
                    if (!markNU()) {
                        break lab18;
                    }
                }
                lab21:
                {
                    int v11 = limit - cursor;
                    lab22:
                    {
                        if (!markSU()) {
                            break lab22;
                        }
                        bra = cursor;
                        sliceDel();
                        int v12 = limit - cursor;
                        lab23:
                        {
                            ket = cursor;
                            if (!markLAr()) {
                                cursor = limit - v12;
                                break lab23;
                            }
                            bra = cursor;
                            sliceDel();
                            if (!stemSuffixChainBeforeKi()) {
                                cursor = limit - v12;
                                break lab23;
                            }
                        }
                        break lab21;
                    }
                    cursor = limit - v11;
                    if (!markLArI()) {
                        break lab18;
                    }
                }
                break lab0;
            }
            cursor = limit - v1;
            lab24:
            {
                ket = cursor;
                if (!markDAn()) {
                    break lab24;
                }
                bra = cursor;
                sliceDel();
                int v13 = limit - cursor;
                lab25:
                {
                    ket = cursor;
                    lab26:
                    {
                        int v14 = limit - cursor;
                        lab27:
                        {
                            if (!markPossessives()) {
                                break lab27;
                            }
                            bra = cursor;
                            sliceDel();
                            int v15 = limit - cursor;
                            lab28:
                            {
                                ket = cursor;
                                if (!markLAr()) {
                                    cursor = limit - v15;
                                    break lab28;
                                }
                                bra = cursor;
                                sliceDel();
                                if (!stemSuffixChainBeforeKi()) {
                                    cursor = limit - v15;
                                    break lab28;
                                }
                            }
                            break lab26;
                        }
                        cursor = limit - v14;
                        lab29:
                        {
                            if (!markLAr()) {
                                break lab29;
                            }
                            bra = cursor;
                            sliceDel();
                            int v16 = limit - cursor;
                            lab30:
                            {
                                if (!stemSuffixChainBeforeKi()) {
                                    cursor = limit - v16;
                                    break lab30;
                                }
                            }
                            break lab26;
                        }
                        cursor = limit - v14;
                        if (!stemSuffixChainBeforeKi()) {
                            cursor = limit - v13;
                            break lab25;
                        }
                    }
                }
                break lab0;
            }
            cursor = limit - v1;
            lab31:
            {
                ket = cursor;
                lab32:
                {
                    int v17 = limit - cursor;
                    lab33:
                    {
                        if (!markNUn()) {
                            break lab33;
                        }
                        break lab32;
                    }
                    cursor = limit - v17;
                    if (!markYlA()) {
                        break lab31;
                    }
                }
                bra = cursor;
                sliceDel();
                int v18 = limit - cursor;
                lab34:
                {
                    lab35:
                    {
                        int v19 = limit - cursor;
                        lab36:
                        {
                            ket = cursor;
                            if (!markLAr()) {
                                break lab36;
                            }
                            bra = cursor;
                            sliceDel();
                            if (!stemSuffixChainBeforeKi()) {
                                break lab36;
                            }
                            break lab35;
                        }
                        cursor = limit - v19;
                        lab37:
                        {
                            ket = cursor;
                            lab38:
                            {
                                int v20 = limit - cursor;
                                lab39:
                                {
                                    if (!markPossessives()) {
                                        break lab39;
                                    }
                                    break lab38;
                                }
                                cursor = limit - v20;
                                if (!markSU()) {
                                    break lab37;
                                }
                            }
                            bra = cursor;
                            sliceDel();
                            int v21 = limit - cursor;
                            lab40:
                            {
                                ket = cursor;
                                if (!markLAr()) {
                                    cursor = limit - v21;
                                    break lab40;
                                }
                                bra = cursor;
                                sliceDel();
                                if (!stemSuffixChainBeforeKi()) {
                                    cursor = limit - v21;
                                    break lab40;
                                }
                            }
                            break lab35;
                        }
                        cursor = limit - v19;
                        if (!stemSuffixChainBeforeKi()) {
                            cursor = limit - v18;
                            break lab34;
                        }
                    }
                }
                break lab0;
            }
            cursor = limit - v1;
            lab41:
            {
                ket = cursor;
                if (!markLArI()) {
                    break lab41;
                }
                bra = cursor;
                sliceDel();
                break lab0;
            }
            cursor = limit - v1;
            lab42:
            {
                if (!stemSuffixChainBeforeKi()) {
                    break lab42;
                }
                break lab0;
            }
            cursor = limit - v1;
            lab43:
            {
                ket = cursor;
                lab44:
                {
                    int v22 = limit - cursor;
                    lab45:
                    {
                        if (!markDA()) {
                            break lab45;
                        }
                        break lab44;
                    }
                    cursor = limit - v22;
                    lab46:
                    {
                        if (!markYU()) {
                            break lab46;
                        }
                        break lab44;
                    }
                    cursor = limit - v22;
                    if (!markYA()) {
                        break lab43;
                    }
                }
                bra = cursor;
                sliceDel();
                int v23 = limit - cursor;
                lab47:
                {
                    ket = cursor;
                    lab48:
                    {
                        int v24 = limit - cursor;
                        lab49:
                        {
                            if (!markPossessives()) {
                                break lab49;
                            }
                            bra = cursor;
                            sliceDel();
                            int v25 = limit - cursor;
                            lab50:
                            {
                                ket = cursor;
                                if (!markLAr()) {
                                    cursor = limit - v25;
                                    break lab50;
                                }
                            }
                            break lab48;
                        }
                        cursor = limit - v24;
                        if (!markLAr()) {
                            cursor = limit - v23;
                            break lab47;
                        }
                    }
                    bra = cursor;
                    sliceDel();
                    ket = cursor;
                    if (!stemSuffixChainBeforeKi()) {
                        cursor = limit - v23;
                        break lab47;
                    }
                }
                break lab0;
            }
            cursor = limit - v1;
            ket = cursor;
            lab51:
            {
                int v26 = limit - cursor;
                lab52:
                {
                    if (!markPossessives()) {
                        break lab52;
                    }
                    break lab51;
                }
                cursor = limit - v26;
                if (!markSU()) {
                    return false;
                }
            }
            bra = cursor;
            sliceDel();
            int v27 = limit - cursor;
            lab53:
            {
                ket = cursor;
                if (!markLAr()) {
                    cursor = limit - v27;
                    break lab53;
                }
                bra = cursor;
                sliceDel();
                if (!stemSuffixChainBeforeKi()) {
                    cursor = limit - v27;
                    break lab53;
                }
            }
        }
        return true;
    }

    private boolean postProcessLastConsonants() {
        ket = cursor;
        int a = findAmongB(LAST_CONSONANTS);
        if (a == 0) {
            return false;
        }
        bra = cursor;
        switch (a) {
            case 1 -> sliceFrom("p");
            case 2 -> sliceFrom("ç");
            case 3 -> sliceFrom("t");
            case 4 -> sliceFrom("k");
            default -> {
            }
        }
        return true;
    }

    private boolean appendUToStemsEndingWithDOrG() {
        ket = cursor;
        bra = cursor;
        lab0:
        {
            int v1 = limit - cursor;
            lab1:
            {
                if (!eqSB("d")) {
                    break lab1;
                }
                break lab0;
            }
            cursor = limit - v1;
            if (!eqSB("g")) {
                return false;
            }
        }
        if (!goOutGroupingB(V)) {
            return false;
        }
        lab2:
        {
            int v2 = limit - cursor;
            lab3:
            {
                lab4:
                {
                    int v3 = limit - cursor;
                    lab5:
                    {
                        if (!eqSB("a")) {
                            break lab5;
                        }
                        break lab4;
                    }
                    cursor = limit - v3;
                    if (!eqSB("ı")) {
                        break lab3;
                    }
                }
                sliceFrom("ı");
                break lab2;
            }
            cursor = limit - v2;
            lab6:
            {
                lab7:
                {
                    int v4 = limit - cursor;
                    lab8:
                    {
                        if (!eqSB("e")) {
                            break lab8;
                        }
                        break lab7;
                    }
                    cursor = limit - v4;
                    if (!eqSB("i")) {
                        break lab6;
                    }
                }
                sliceFrom("i");
                break lab2;
            }
            cursor = limit - v2;
            lab9:
            {
                lab10:
                {
                    int v5 = limit - cursor;
                    lab11:
                    {
                        if (!eqSB("o")) {
                            break lab11;
                        }
                        break lab10;
                    }
                    cursor = limit - v5;
                    if (!eqSB("u")) {
                        break lab9;
                    }
                }
                sliceFrom("u");
                break lab2;
            }
            cursor = limit - v2;
            lab12:
            {
                int v6 = limit - cursor;
                lab13:
                {
                    if (!eqSB("ö")) {
                        break lab13;
                    }
                    break lab12;
                }
                cursor = limit - v6;
                if (!eqSB("ü")) {
                    return false;
                }
            }
            sliceFrom("ü");
        }
        return true;
    }

    private boolean isReservedWord() {
        if (!eqSB("ad")) {
            return false;
        }
        int v1 = limit - cursor;
        lab0:
        {
            if (!eqSB("soy")) {
                cursor = limit - v1;
                break lab0;
            }
        }
        return cursor <= limitBackward;
    }

    private boolean removeProperNounSuffix() {
        int v1 = cursor;
        lab0:
        {
            bra = cursor;
            golab1:
            while (true) {
                int v2 = cursor;
                lab2:
                {
                    int v3 = cursor;
                    lab3:
                    {
                        if (!eqS("'")) {
                            break lab3;
                        }
                        break lab2;
                    }
                    cursor = v3;
                    cursor = v2;
                    break golab1;
                }
                cursor = v2;
                if (cursor >= limit) {
                    break lab0;
                }
                cursor++;
            }
            ket = cursor;
            sliceDel();
        }
        cursor = v1;
        int v4 = cursor;
        lab4:
        {
            int c = cursor + 2;
            if (c > limit) {
                break lab4;
            }
            cursor = c;
            golab5:
            while (true) {
                int v5 = cursor;
                lab6:
                {
                    if (!eqS("'")) {
                        break lab6;
                    }
                    cursor = v5;
                    break golab5;
                }
                cursor = v5;
                if (cursor >= limit) {
                    break lab4;
                }
                cursor++;
            }
            bra = cursor;
            cursor = limit;
            ket = cursor;
            sliceDel();
        }
        cursor = v4;
        return true;
    }

    private boolean moreThanOneSyllableWord() {
        int v1 = cursor;
        for (int i = 0; i < 2; i++) {
            if (!goPastIn(V)) {
                cursor = v1;
                return false;
            }
        }
        cursor = v1;
        return true;
    }

    private void postlude() {
        limitBackward = cursor;
        cursor = limit;
        {
            int v1 = limit - cursor;
            lab0:
            {
                if (!isReservedWord()) {
                    break lab0;
                }
                cursor = limitBackward;
                return;
            }
            cursor = limit - v1;
        }
        int v2 = limit - cursor;
        appendUToStemsEndingWithDOrG();
        cursor = limit - v2;
        int v3 = limit - cursor;
        postProcessLastConsonants();
        cursor = limit - v3;
        cursor = limitBackward;
    }
}
