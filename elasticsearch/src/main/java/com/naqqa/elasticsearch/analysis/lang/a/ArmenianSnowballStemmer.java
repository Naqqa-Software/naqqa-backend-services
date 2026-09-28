package com.naqqa.elasticsearch.analysis.lang.a;

import com.naqqa.elasticsearch.analysis.stem.snowball.SnowballSupport;

public final class ArmenianSnowballStemmer extends SnowballSupport {

    private static final String V = "աեէըիուօ";

    private static final Among ADJECTIVE = Among.of(g(
        "րորդ", "երորդ", "ալի", "ակի",
        "որակ", "եղ", "ական", "արան",
        "են", "եկեն", "երեն", "որէն",
        "ին", "գին", "ովին", "լայն",
        "վուն", "պես", "իվ", "ատ", "ավետ",
        "կոտ", "բար"));

    private static final Among VERB = Among.of(g(
        "ա", "ացա", "եցա", "վե", "ացրի",
        "ացի", "եցի", "վեցի", "ալ", "ըալ",
        "անալ", "ենալ", "ացնալ", "ել",
        "ըել", "նել", "ցնել", "եցնել",
        "չել", "վել", "ացվել", "եցվել",
        "տել", "ատել", "ոտել", "կոտել",
        "ված", "ում", "վում", "ան", "ցան",
        "ացան", "ացրին", "ացին",
        "եցին", "վեցին", "ալիս",
        "ելիս", "ավ", "ացավ", "եցավ",
        "ալով", "ելով", "ար", "ացար",
        "եցար", "ացրիր", "ացիր",
        "եցիր", "վեցիր", "աց", "եց",
        "ացրեց", "ալուց", "ելուց",
        "ալու", "ելու", "աք", "ցաք",
        "ացաք", "ացրիք", "ացիք",
        "եցիք", "վեցիք", "անք", "ցանք",
        "ացանք", "ացրինք", "ացինք",
        "եցինք", "վեցինք"));

    private static final Among NOUN = Among.of(g(
        "որդ", "ույթ", "ուհի", "ցի",
        "իլ", "ակ", "յակ", "անակ", "իկ",
        "ուկ", "ան", "պան", "ստան",
        "արան", "եղէն", "յուն",
        "ություն", "ածո", "իչ", "ուս",
        "ուստ", "գար", "վոր", "ավոր",
        "ոց", "անօց", "ու", "ք", "չեք",
        "իք", "ալիք", "անիք", "վածք",
        "ույք", "ենք", "ոնք", "ունք",
        "մունք", "իչք", "արք"));

    private static final Among ENDING = Among.of(g(
        "սա", "վա", "ամբ", "դ", "անդ",
        "ությանդ", "վանդ", "ոջդ",
        "երդ", "ներդ", "ուդ", "ը", "անը",
        "ությանը", "վանը", "ոջը",
        "երը", "ները", "ի", "վի", "երի",
        "ների", "անում", "երում",
        "ներում", "ն", "ան", "ության",
        "վան", "ին", "երին", "ներին",
        "ությանն", "երն", "ներն",
        "ուն", "ոջ", "ությանս",
        "վանս", "ոջս", "ով", "անով",
        "վով", "երով", "ներով", "եր",
        "ներ", "ց", "ից", "վանից", "ոջից",
        "վից", "երից", "ներից", "ցից",
        "ոց", "ուց"));

    private int p2;
    private int pV;

    public ArmenianSnowballStemmer() {
    }

    @Override
    protected void run() {
        markRegions();
        limitBackward = cursor;
        cursor = limit;
        if (cursor < pV) {
            return;
        }
        int lb = limitBackward;
        limitBackward = pV;
        try {
            int v2 = limit - cursor;
            ending();
            cursor = limit - v2;
            int v3 = limit - cursor;
            verb();
            cursor = limit - v3;
            int v4 = limit - cursor;
            adjective();
            cursor = limit - v4;
            int v5 = limit - cursor;
            noun();
            cursor = limit - v5;
        } finally {
            limitBackward = lb;
        }
        cursor = limitBackward;
    }

    private void markRegions() {
        pV = limit;
        p2 = limit;
        int c = cursor;
        if (goPastIn(V)) {
            pV = cursor;
            if (goPastOut(V) && goPastIn(V) && goPastOut(V)) {
                p2 = cursor;
            }
        }
        cursor = c;
    }

    private boolean r2() {
        return p2 <= cursor;
    }

    private boolean adjective() {
        ket = cursor;
        if (findAmongB(ADJECTIVE) == 0) {
            return false;
        }
        bra = cursor;
        sliceDel();
        return true;
    }

    private boolean verb() {
        ket = cursor;
        if (findAmongB(VERB) == 0) {
            return false;
        }
        bra = cursor;
        sliceDel();
        return true;
    }

    private boolean noun() {
        ket = cursor;
        if (findAmongB(NOUN) == 0) {
            return false;
        }
        bra = cursor;
        sliceDel();
        return true;
    }

    private boolean ending() {
        ket = cursor;
        if (findAmongB(ENDING) == 0) {
            return false;
        }
        bra = cursor;
        if (!r2()) {
            return false;
        }
        sliceDel();
        return true;
    }
}
