package com.naqqa.elasticsearch.analysis.stem.light;

import com.naqqa.elasticsearch.analysis.stem.light.RslpPluralStep.Rule;

public final class PortugueseMinimalStemmer extends CharArrayStemmer {

    private static final RslpPluralStep PLURAL = new RslpPluralStep(3,
        new Rule("ns", 1, "m"),
        new Rule("ões", 3, "ão"),
        new Rule("ães", 1, "ão", "mães"),
        new Rule("ais", 1, "al", "cais", "mais"),
        new Rule("éis", 2, "el"),
        new Rule("eis", 2, "el"),
        new Rule("óis", 2, "ol"),
        new Rule("is", 2, "il", "lápis", "cais", "mais", "crúcis", "biquínis", "pois", "depois", "dois", "leis"),
        new Rule("les", 3, "l"),
        new Rule("res", 3, "r", "árvores"),
        new Rule("s", 2, "", "aliás", "pires", "lápis", "cais", "mais", "mas", "menos", "férias", "fezes",
            "pêsames", "crúcis", "gás", "atrás", "moisés", "através", "convés", "ês", "país", "após", "ambas",
            "ambos", "messias", "depois"));

    public PortugueseMinimalStemmer() {
    }

    @Override
    public int stem(char[] s, int len) {
        return PLURAL.apply(s, len);
    }
}
