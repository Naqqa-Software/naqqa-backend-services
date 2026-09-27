package com.naqqa.elasticsearch.analysis.stem.light;

import com.naqqa.elasticsearch.analysis.stem.light.RslpPluralStep.Rule;

public final class GalicianMinimalStemmer extends CharArrayStemmer {

    private static final RslpPluralStep PLURAL = new RslpPluralStep(3,
        new Rule("ns", 1, "n", "luns", "furatapóns", "furatapons"),
        new Rule("ós", 3, "ón"),
        new Rule("ões", 3, "ón"),
        new Rule("ães", 1, "ão", "mães", "magalhães"),
        new Rule("ais", 2, "al", "cais", "tais", "mais", "pais", "ademais"),
        new Rule("áis", 2, "al", "cáis", "táis", "máis", "páis", "ademáis"),
        new Rule("éis", 2, "el"),
        new Rule("eis", 2, "el"),
        new Rule("óis", 2, "ol", "escornabóis"),
        new Rule("ois", 2, "ol", "escornabois"),
        new Rule("ís", 2, "il", "país"),
        new Rule("is", 2, "il", "menfis", "pais", "kinguis"),
        new Rule("les", 2, "l", "ingles", "marselles", "montreales", "senegales", "manizales", "móstoles", "nápoles"),
        new Rule("res", 3, "r", "petres", "henares", "cáceres", "baleares", "linares", "londres", "mieres",
            "miraflores", "mércores", "venres", "pires"),
        new Rule("ces", 2, "z"),
        new Rule("zes", 2, "z"),
        new Rule("ises", 3, "z"),
        new Rule("ás", 1, "al", "más"),
        new Rule("ses", 2, "s"),
        new Rule("s", 2, "", "barbadés", "barcelonés", "cantonés", "gabonés", "llanés", "medinés", "escocés",
            "escocês", "francês", "barcelonês", "cantonês", "macramés", "reves", "barcelones", "cantones",
            "gabones", "llanes", "magallanes", "medines", "escoces", "frances", "xoves", "martes", "aliás",
            "pires", "lápis", "cais", "mais", "mas", "menos", "férias", "pêsames", "crúcis", "país", "cangas",
            "atenas", "asturias", "canarias", "filipinas", "honduras", "molucas", "caldas", "mascareñas",
            "micenas", "covarrubias", "psoas", "óculos", "nupcias"));

    public GalicianMinimalStemmer() {
    }

    @Override
    public int stem(char[] s, int len) {
        return PLURAL.apply(s, len);
    }
}
