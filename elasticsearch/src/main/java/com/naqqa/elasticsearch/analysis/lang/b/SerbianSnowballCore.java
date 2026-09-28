
package com.naqqa.elasticsearch.analysis.lang.b;


public class SerbianSnowballCore extends SnowballProgram {

  private static final long serialVersionUID = 1L;

  private static final SnowballAmong[] a_0 = {
    new SnowballAmong("\u0430", -1, 1),
    new SnowballAmong("\u0431", -1, 2),
    new SnowballAmong("\u0432", -1, 3),
    new SnowballAmong("\u0433", -1, 4),
    new SnowballAmong("\u0434", -1, 5),
    new SnowballAmong("\u0435", -1, 7),
    new SnowballAmong("\u0436", -1, 8),
    new SnowballAmong("\u0437", -1, 9),
    new SnowballAmong("\u0438", -1, 10),
    new SnowballAmong("\u043A", -1, 12),
    new SnowballAmong("\u043B", -1, 13),
    new SnowballAmong("\u043C", -1, 15),
    new SnowballAmong("\u043D", -1, 16),
    new SnowballAmong("\u043E", -1, 18),
    new SnowballAmong("\u043F", -1, 19),
    new SnowballAmong("\u0440", -1, 20),
    new SnowballAmong("\u0441", -1, 21),
    new SnowballAmong("\u0442", -1, 22),
    new SnowballAmong("\u0443", -1, 24),
    new SnowballAmong("\u0444", -1, 25),
    new SnowballAmong("\u0445", -1, 26),
    new SnowballAmong("\u0446", -1, 27),
    new SnowballAmong("\u0447", -1, 28),
    new SnowballAmong("\u0448", -1, 30),
    new SnowballAmong("\u0452", -1, 6),
    new SnowballAmong("\u0458", -1, 11),
    new SnowballAmong("\u0459", -1, 14),
    new SnowballAmong("\u045A", -1, 17),
    new SnowballAmong("\u045B", -1, 23),
    new SnowballAmong("\u045F", -1, 29)
  };

  private static final SnowballAmong[] a_1 = {
    new SnowballAmong("daba", -1, 73),
    new SnowballAmong("ajaca", -1, 12),
    new SnowballAmong("ejaca", -1, 14),
    new SnowballAmong("ljaca", -1, 13),
    new SnowballAmong("njaca", -1, 85),
    new SnowballAmong("ojaca", -1, 15),
    new SnowballAmong("alaca", -1, 82),
    new SnowballAmong("elaca", -1, 83),
    new SnowballAmong("olaca", -1, 84),
    new SnowballAmong("maca", -1, 75),
    new SnowballAmong("naca", -1, 76),
    new SnowballAmong("raca", -1, 81),
    new SnowballAmong("saca", -1, 80),
    new SnowballAmong("vaca", -1, 79),
    new SnowballAmong("\u0161aca", -1, 18),
    new SnowballAmong("aoca", -1, 82),
    new SnowballAmong("acaka", -1, 55),
    new SnowballAmong("ajaka", -1, 16),
    new SnowballAmong("ojaka", -1, 17),
    new SnowballAmong("anaka", -1, 78),
    new SnowballAmong("ataka", -1, 58),
    new SnowballAmong("etaka", -1, 59),
    new SnowballAmong("itaka", -1, 60),
    new SnowballAmong("otaka", -1, 61),
    new SnowballAmong("utaka", -1, 62),
    new SnowballAmong("a\u010Daka", -1, 54),
    new SnowballAmong("esama", -1, 67),
    new SnowballAmong("izama", -1, 87),
    new SnowballAmong("jacima", -1, 5),
    new SnowballAmong("nicima", -1, 23),
    new SnowballAmong("ticima", -1, 24),
    new SnowballAmong("teticima", 30, 21),
    new SnowballAmong("zicima", -1, 25),
    new SnowballAmong("atcima", -1, 58),
    new SnowballAmong("utcima", -1, 62),
    new SnowballAmong("\u010Dcima", -1, 74),
    new SnowballAmong("pesima", -1, 2),
    new SnowballAmong("inzima", -1, 19),
    new SnowballAmong("lozima", -1, 1),
    new SnowballAmong("metara", -1, 68),
    new SnowballAmong("centara", -1, 69),
    new SnowballAmong("istara", -1, 70),
    new SnowballAmong("ekata", -1, 86),
    new SnowballAmong("anata", -1, 53),
    new SnowballAmong("nstava", -1, 22),
    new SnowballAmong("kustava", -1, 29),
    new SnowballAmong("ajac", -1, 12),
    new SnowballAmong("ejac", -1, 14),
    new SnowballAmong("ljac", -1, 13),
    new SnowballAmong("njac", -1, 85),
    new SnowballAmong("anjac", 49, 11),
    new SnowballAmong("ojac", -1, 15),
    new SnowballAmong("alac", -1, 82),
    new SnowballAmong("elac", -1, 83),
    new SnowballAmong("olac", -1, 84),
    new SnowballAmong("mac", -1, 75),
    new SnowballAmong("nac", -1, 76),
    new SnowballAmong("rac", -1, 81),
    new SnowballAmong("sac", -1, 80),
    new SnowballAmong("vac", -1, 79),
    new SnowballAmong("\u0161ac", -1, 18),
    new SnowballAmong("jebe", -1, 88),
    new SnowballAmong("olce", -1, 84),
    new SnowballAmong("kuse", -1, 27),
    new SnowballAmong("rave", -1, 42),
    new SnowballAmong("save", -1, 52),
    new SnowballAmong("\u0161ave", -1, 51),
    new SnowballAmong("baci", -1, 89),
    new SnowballAmong("jaci", -1, 5),
    new SnowballAmong("tvenici", -1, 20),
    new SnowballAmong("snici", -1, 26),
    new SnowballAmong("tetici", -1, 21),
    new SnowballAmong("bojci", -1, 4),
    new SnowballAmong("vojci", -1, 3),
    new SnowballAmong("ojsci", -1, 66),
    new SnowballAmong("atci", -1, 58),
    new SnowballAmong("itci", -1, 60),
    new SnowballAmong("utci", -1, 62),
    new SnowballAmong("\u010Dci", -1, 74),
    new SnowballAmong("pesi", -1, 2),
    new SnowballAmong("inzi", -1, 19),
    new SnowballAmong("lozi", -1, 1),
    new SnowballAmong("acak", -1, 55),
    new SnowballAmong("usak", -1, 57),
    new SnowballAmong("atak", -1, 58),
    new SnowballAmong("etak", -1, 59),
    new SnowballAmong("itak", -1, 60),
    new SnowballAmong("otak", -1, 61),
    new SnowballAmong("utak", -1, 62),
    new SnowballAmong("a\u010Dak", -1, 54),
    new SnowballAmong("u\u0161ak", -1, 56),
    new SnowballAmong("izam", -1, 87),
    new SnowballAmong("tican", -1, 65),
    new SnowballAmong("cajan", -1, 7),
    new SnowballAmong("\u010Dajan", -1, 6),
    new SnowballAmong("voljan", -1, 77),
    new SnowballAmong("eskan", -1, 63),
    new SnowballAmong("alan", -1, 40),
    new SnowballAmong("bilan", -1, 33),
    new SnowballAmong("gilan", -1, 37),
    new SnowballAmong("nilan", -1, 39),
    new SnowballAmong("rilan", -1, 38),
    new SnowballAmong("silan", -1, 36),
    new SnowballAmong("tilan", -1, 34),
    new SnowballAmong("avilan", -1, 35),
    new SnowballAmong("laran", -1, 9),
    new SnowballAmong("eran", -1, 8),
    new SnowballAmong("asan", -1, 91),
    new SnowballAmong("esan", -1, 10),
    new SnowballAmong("dusan", -1, 31),
    new SnowballAmong("kusan", -1, 28),
    new SnowballAmong("atan", -1, 47),
    new SnowballAmong("pletan", -1, 50),
    new SnowballAmong("tetan", -1, 49),
    new SnowballAmong("antan", -1, 32),
    new SnowballAmong("pravan", -1, 44),
    new SnowballAmong("stavan", -1, 43),
    new SnowballAmong("sivan", -1, 46),
    new SnowballAmong("tivan", -1, 45),
    new SnowballAmong("ozan", -1, 41),
    new SnowballAmong("ti\u010Dan", -1, 64),
    new SnowballAmong("a\u0161an", -1, 90),
    new SnowballAmong("du\u0161an", -1, 30),
    new SnowballAmong("metar", -1, 68),
    new SnowballAmong("centar", -1, 69),
    new SnowballAmong("istar", -1, 70),
    new SnowballAmong("ekat", -1, 86),
    new SnowballAmong("enat", -1, 48),
    new SnowballAmong("oscu", -1, 72),
    new SnowballAmong("o\u0161\u0107u", -1, 71)
  };

  private static final SnowballAmong[] a_2 = {
    new SnowballAmong("aca", -1, 124),
    new SnowballAmong("eca", -1, 125),
    new SnowballAmong("uca", -1, 126),
    new SnowballAmong("ga", -1, 20),
    new SnowballAmong("acega", 3, 124),
    new SnowballAmong("ecega", 3, 125),
    new SnowballAmong("ucega", 3, 126),
    new SnowballAmong("anjijega", 3, 84),
    new SnowballAmong("enjijega", 3, 85),
    new SnowballAmong("snjijega", 3, 122),
    new SnowballAmong("\u0161njijega", 3, 86),
    new SnowballAmong("kijega", 3, 95),
    new SnowballAmong("skijega", 11, 1),
    new SnowballAmong("\u0161kijega", 11, 2),
    new SnowballAmong("elijega", 3, 83),
    new SnowballAmong("nijega", 3, 13),
    new SnowballAmong("osijega", 3, 123),
    new SnowballAmong("atijega", 3, 120),
    new SnowballAmong("evitijega", 3, 92),
    new SnowballAmong("ovitijega", 3, 93),
    new SnowballAmong("astijega", 3, 94),
    new SnowballAmong("avijega", 3, 77),
    new SnowballAmong("evijega", 3, 78),
    new SnowballAmong("ivijega", 3, 79),
    new SnowballAmong("ovijega", 3, 80),
    new SnowballAmong("o\u0161ijega", 3, 91),
    new SnowballAmong("anjega", 3, 84),
    new SnowballAmong("enjega", 3, 85),
    new SnowballAmong("snjega", 3, 122),
    new SnowballAmong("\u0161njega", 3, 86),
    new SnowballAmong("kega", 3, 95),
    new SnowballAmong("skega", 30, 1),
    new SnowballAmong("\u0161kega", 30, 2),
    new SnowballAmong("elega", 3, 83),
    new SnowballAmong("nega", 3, 13),
    new SnowballAmong("anega", 34, 10),
    new SnowballAmong("enega", 34, 87),
    new SnowballAmong("snega", 34, 159),
    new SnowballAmong("\u0161nega", 34, 88),
    new SnowballAmong("osega", 3, 123),
    new SnowballAmong("atega", 3, 120),
    new SnowballAmong("evitega", 3, 92),
    new SnowballAmong("ovitega", 3, 93),
    new SnowballAmong("astega", 3, 94),
    new SnowballAmong("avega", 3, 77),
    new SnowballAmong("evega", 3, 78),
    new SnowballAmong("ivega", 3, 79),
    new SnowballAmong("ovega", 3, 80),
    new SnowballAmong("a\u0107ega", 3, 14),
    new SnowballAmong("e\u0107ega", 3, 15),
    new SnowballAmong("u\u0107ega", 3, 16),
    new SnowballAmong("o\u0161ega", 3, 91),
    new SnowballAmong("acoga", 3, 124),
    new SnowballAmong("ecoga", 3, 125),
    new SnowballAmong("ucoga", 3, 126),
    new SnowballAmong("anjoga", 3, 84),
    new SnowballAmong("enjoga", 3, 85),
    new SnowballAmong("snjoga", 3, 122),
    new SnowballAmong("\u0161njoga", 3, 86),
    new SnowballAmong("koga", 3, 95),
    new SnowballAmong("skoga", 59, 1),
    new SnowballAmong("\u0161koga", 59, 2),
    new SnowballAmong("loga", 3, 19),
    new SnowballAmong("eloga", 62, 83),
    new SnowballAmong("noga", 3, 13),
    new SnowballAmong("cinoga", 64, 137),
    new SnowballAmong("\u010Dinoga", 64, 89),
    new SnowballAmong("osoga", 3, 123),
    new SnowballAmong("atoga", 3, 120),
    new SnowballAmong("evitoga", 3, 92),
    new SnowballAmong("ovitoga", 3, 93),
    new SnowballAmong("astoga", 3, 94),
    new SnowballAmong("avoga", 3, 77),
    new SnowballAmong("evoga", 3, 78),
    new SnowballAmong("ivoga", 3, 79),
    new SnowballAmong("ovoga", 3, 80),
    new SnowballAmong("a\u0107oga", 3, 14),
    new SnowballAmong("e\u0107oga", 3, 15),
    new SnowballAmong("u\u0107oga", 3, 16),
    new SnowballAmong("o\u0161oga", 3, 91),
    new SnowballAmong("uga", 3, 18),
    new SnowballAmong("aja", -1, 109),
    new SnowballAmong("caja", 81, 26),
    new SnowballAmong("laja", 81, 30),
    new SnowballAmong("raja", 81, 31),
    new SnowballAmong("\u0107aja", 81, 28),
    new SnowballAmong("\u010Daja", 81, 27),
    new SnowballAmong("\u0111aja", 81, 29),
    new SnowballAmong("bija", -1, 32),
    new SnowballAmong("cija", -1, 33),
    new SnowballAmong("dija", -1, 34),
    new SnowballAmong("fija", -1, 40),
    new SnowballAmong("gija", -1, 39),
    new SnowballAmong("anjija", -1, 84),
    new SnowballAmong("enjija", -1, 85),
    new SnowballAmong("snjija", -1, 122),
    new SnowballAmong("\u0161njija", -1, 86),
    new SnowballAmong("kija", -1, 95),
    new SnowballAmong("skija", 97, 1),
    new SnowballAmong("\u0161kija", 97, 2),
    new SnowballAmong("lija", -1, 24),
    new SnowballAmong("elija", 100, 83),
    new SnowballAmong("mija", -1, 37),
    new SnowballAmong("nija", -1, 13),
    new SnowballAmong("ganija", 103, 9),
    new SnowballAmong("manija", 103, 6),
    new SnowballAmong("panija", 103, 7),
    new SnowballAmong("ranija", 103, 8),
    new SnowballAmong("tanija", 103, 5),
    new SnowballAmong("pija", -1, 41),
    new SnowballAmong("rija", -1, 42),
    new SnowballAmong("rarija", 110, 21),
    new SnowballAmong("sija", -1, 23),
    new SnowballAmong("osija", 112, 123),
    new SnowballAmong("tija", -1, 44),
    new SnowballAmong("atija", 114, 120),
    new SnowballAmong("evitija", 114, 92),
    new SnowballAmong("ovitija", 114, 93),
    new SnowballAmong("otija", 114, 22),
    new SnowballAmong("astija", 114, 94),
    new SnowballAmong("avija", -1, 77),
    new SnowballAmong("evija", -1, 78),
    new SnowballAmong("ivija", -1, 79),
    new SnowballAmong("ovija", -1, 80),
    new SnowballAmong("zija", -1, 45),
    new SnowballAmong("o\u0161ija", -1, 91),
    new SnowballAmong("\u017Eija", -1, 38),
    new SnowballAmong("anja", -1, 84),
    new SnowballAmong("enja", -1, 85),
    new SnowballAmong("snja", -1, 122),
    new SnowballAmong("\u0161nja", -1, 86),
    new SnowballAmong("ka", -1, 95),
    new SnowballAmong("ska", 131, 1),
    new SnowballAmong("\u0161ka", 131, 2),
    new SnowballAmong("ala", -1, 104),
    new SnowballAmong("acala", 134, 128),
    new SnowballAmong("astajala", 134, 106),
    new SnowballAmong("istajala", 134, 107),
    new SnowballAmong("ostajala", 134, 108),
    new SnowballAmong("ijala", 134, 47),
    new SnowballAmong("injala", 134, 114),
    new SnowballAmong("nala", 134, 46),
    new SnowballAmong("irala", 134, 100),
    new SnowballAmong("urala", 134, 105),
    new SnowballAmong("tala", 134, 113),
    new SnowballAmong("astala", 144, 110),
    new SnowballAmong("istala", 144, 111),
    new SnowballAmong("ostala", 144, 112),
    new SnowballAmong("avala", 134, 97),
    new SnowballAmong("evala", 134, 96),
    new SnowballAmong("ivala", 134, 98),
    new SnowballAmong("ovala", 134, 76),
    new SnowballAmong("uvala", 134, 99),
    new SnowballAmong("a\u010Dala", 134, 102),
    new SnowballAmong("ela", -1, 83),
    new SnowballAmong("ila", -1, 116),
    new SnowballAmong("acila", 155, 124),
    new SnowballAmong("lucila", 155, 121),
    new SnowballAmong("nila", 155, 103),
    new SnowballAmong("astanila", 158, 110),
    new SnowballAmong("istanila", 158, 111),
    new SnowballAmong("ostanila", 158, 112),
    new SnowballAmong("rosila", 155, 127),
    new SnowballAmong("jetila", 155, 118),
    new SnowballAmong("ozila", 155, 48),
    new SnowballAmong("a\u010Dila", 155, 101),
    new SnowballAmong("lu\u010Dila", 155, 117),
    new SnowballAmong("ro\u0161ila", 155, 90),
    new SnowballAmong("ola", -1, 50),
    new SnowballAmong("asla", -1, 115),
    new SnowballAmong("nula", -1, 13),
    new SnowballAmong("gama", -1, 20),
    new SnowballAmong("logama", 171, 19),
    new SnowballAmong("ugama", 171, 18),
    new SnowballAmong("ajama", -1, 109),
    new SnowballAmong("cajama", 174, 26),
    new SnowballAmong("lajama", 174, 30),
    new SnowballAmong("rajama", 174, 31),
    new SnowballAmong("\u0107ajama", 174, 28),
    new SnowballAmong("\u010Dajama", 174, 27),
    new SnowballAmong("\u0111ajama", 174, 29),
    new SnowballAmong("bijama", -1, 32),
    new SnowballAmong("cijama", -1, 33),
    new SnowballAmong("dijama", -1, 34),
    new SnowballAmong("fijama", -1, 40),
    new SnowballAmong("gijama", -1, 39),
    new SnowballAmong("lijama", -1, 35),
    new SnowballAmong("mijama", -1, 37),
    new SnowballAmong("nijama", -1, 36),
    new SnowballAmong("ganijama", 188, 9),
    new SnowballAmong("manijama", 188, 6),
    new SnowballAmong("panijama", 188, 7),
    new SnowballAmong("ranijama", 188, 8),
    new SnowballAmong("tanijama", 188, 5),
    new SnowballAmong("pijama", -1, 41),
    new SnowballAmong("rijama", -1, 42),
    new SnowballAmong("sijama", -1, 43),
    new SnowballAmong("tijama", -1, 44),
    new SnowballAmong("zijama", -1, 45),
    new SnowballAmong("\u017Eijama", -1, 38),
    new SnowballAmong("alama", -1, 104),
    new SnowballAmong("ijalama", 200, 47),
    new SnowballAmong("nalama", 200, 46),
    new SnowballAmong("elama", -1, 119),
    new SnowballAmong("ilama", -1, 116),
    new SnowballAmong("ramama", -1, 52),
    new SnowballAmong("lemama", -1, 51),
    new SnowballAmong("inama", -1, 11),
    new SnowballAmong("cinama", 207, 137),
    new SnowballAmong("\u010Dinama", 207, 89),
    new SnowballAmong("rama", -1, 52),
    new SnowballAmong("arama", 210, 53),
    new SnowballAmong("drama", 210, 54),
    new SnowballAmong("erama", 210, 55),
    new SnowballAmong("orama", 210, 56),
    new SnowballAmong("basama", -1, 135),
    new SnowballAmong("gasama", -1, 131),
    new SnowballAmong("jasama", -1, 129),
    new SnowballAmong("kasama", -1, 133),
    new SnowballAmong("nasama", -1, 132),
    new SnowballAmong("tasama", -1, 130),
    new SnowballAmong("vasama", -1, 134),
    new SnowballAmong("esama", -1, 152),
    new SnowballAmong("isama", -1, 154),
    new SnowballAmong("etama", -1, 70),
    new SnowballAmong("estama", -1, 71),
    new SnowballAmong("istama", -1, 72),
    new SnowballAmong("kstama", -1, 73),
    new SnowballAmong("ostama", -1, 74),
    new SnowballAmong("avama", -1, 77),
    new SnowballAmong("evama", -1, 78),
    new SnowballAmong("ivama", -1, 79),
    new SnowballAmong("ba\u0161ama", -1, 63),
    new SnowballAmong("ga\u0161ama", -1, 64),
    new SnowballAmong("ja\u0161ama", -1, 61),
    new SnowballAmong("ka\u0161ama", -1, 62),
    new SnowballAmong("na\u0161ama", -1, 60),
    new SnowballAmong("ta\u0161ama", -1, 59),
    new SnowballAmong("va\u0161ama", -1, 65),
    new SnowballAmong("e\u0161ama", -1, 66),
    new SnowballAmong("i\u0161ama", -1, 67),
    new SnowballAmong("lema", -1, 51),
    new SnowballAmong("acima", -1, 124),
    new SnowballAmong("ecima", -1, 125),
    new SnowballAmong("ucima", -1, 126),
    new SnowballAmong("ajima", -1, 109),
    new SnowballAmong("cajima", 245, 26),
    new SnowballAmong("lajima", 245, 30),
    new SnowballAmong("rajima", 245, 31),
    new SnowballAmong("\u0107ajima", 245, 28),
    new SnowballAmong("\u010Dajima", 245, 27),
    new SnowballAmong("\u0111ajima", 245, 29),
    new SnowballAmong("bijima", -1, 32),
    new SnowballAmong("cijima", -1, 33),
    new SnowballAmong("dijima", -1, 34),
    new SnowballAmong("fijima", -1, 40),
    new SnowballAmong("gijima", -1, 39),
    new SnowballAmong("anjijima", -1, 84),
    new SnowballAmong("enjijima", -1, 85),
    new SnowballAmong("snjijima", -1, 122),
    new SnowballAmong("\u0161njijima", -1, 86),
    new SnowballAmong("kijima", -1, 95),
    new SnowballAmong("skijima", 261, 1),
    new SnowballAmong("\u0161kijima", 261, 2),
    new SnowballAmong("lijima", -1, 35),
    new SnowballAmong("elijima", 264, 83),
    new SnowballAmong("mijima", -1, 37),
    new SnowballAmong("nijima", -1, 13),
    new SnowballAmong("ganijima", 267, 9),
    new SnowballAmong("manijima", 267, 6),
    new SnowballAmong("panijima", 267, 7),
    new SnowballAmong("ranijima", 267, 8),
    new SnowballAmong("tanijima", 267, 5),
    new SnowballAmong("pijima", -1, 41),
    new SnowballAmong("rijima", -1, 42),
    new SnowballAmong("sijima", -1, 43),
    new SnowballAmong("osijima", 275, 123),
    new SnowballAmong("tijima", -1, 44),
    new SnowballAmong("atijima", 277, 120),
    new SnowballAmong("evitijima", 277, 92),
    new SnowballAmong("ovitijima", 277, 93),
    new SnowballAmong("astijima", 277, 94),
    new SnowballAmong("avijima", -1, 77),
    new SnowballAmong("evijima", -1, 78),
    new SnowballAmong("ivijima", -1, 79),
    new SnowballAmong("ovijima", -1, 80),
    new SnowballAmong("zijima", -1, 45),
    new SnowballAmong("o\u0161ijima", -1, 91),
    new SnowballAmong("\u017Eijima", -1, 38),
    new SnowballAmong("anjima", -1, 84),
    new SnowballAmong("enjima", -1, 85),
    new SnowballAmong("snjima", -1, 122),
    new SnowballAmong("\u0161njima", -1, 86),
    new SnowballAmong("kima", -1, 95),
    new SnowballAmong("skima", 293, 1),
    new SnowballAmong("\u0161kima", 293, 2),
    new SnowballAmong("alima", -1, 104),
    new SnowballAmong("ijalima", 296, 47),
    new SnowballAmong("nalima", 296, 46),
    new SnowballAmong("elima", -1, 83),
    new SnowballAmong("ilima", -1, 116),
    new SnowballAmong("ozilima", 300, 48),
    new SnowballAmong("olima", -1, 50),
    new SnowballAmong("lemima", -1, 51),
    new SnowballAmong("nima", -1, 13),
    new SnowballAmong("anima", 304, 10),
    new SnowballAmong("inima", 304, 11),
    new SnowballAmong("cinima", 306, 137),
    new SnowballAmong("\u010Dinima", 306, 89),
    new SnowballAmong("onima", 304, 12),
    new SnowballAmong("arima", -1, 53),
    new SnowballAmong("drima", -1, 54),
    new SnowballAmong("erima", -1, 55),
    new SnowballAmong("orima", -1, 56),
    new SnowballAmong("basima", -1, 135),
    new SnowballAmong("gasima", -1, 131),
    new SnowballAmong("jasima", -1, 129),
    new SnowballAmong("kasima", -1, 133),
    new SnowballAmong("nasima", -1, 132),
    new SnowballAmong("tasima", -1, 130),
    new SnowballAmong("vasima", -1, 134),
    new SnowballAmong("esima", -1, 57),
    new SnowballAmong("isima", -1, 58),
    new SnowballAmong("osima", -1, 123),
    new SnowballAmong("atima", -1, 120),
    new SnowballAmong("ikatima", 324, 68),
    new SnowballAmong("latima", 324, 69),
    new SnowballAmong("etima", -1, 70),
    new SnowballAmong("evitima", -1, 92),
    new SnowballAmong("ovitima", -1, 93),
    new SnowballAmong("astima", -1, 94),
    new SnowballAmong("estima", -1, 71),
    new SnowballAmong("istima", -1, 72),
    new SnowballAmong("kstima", -1, 73),
    new SnowballAmong("ostima", -1, 74),
    new SnowballAmong("i\u0161tima", -1, 75),
    new SnowballAmong("avima", -1, 77),
    new SnowballAmong("evima", -1, 78),
    new SnowballAmong("ajevima", 337, 109),
    new SnowballAmong("cajevima", 338, 26),
    new SnowballAmong("lajevima", 338, 30),
    new SnowballAmong("rajevima", 338, 31),
    new SnowballAmong("\u0107ajevima", 338, 28),
    new SnowballAmong("\u010Dajevima", 338, 27),
    new SnowballAmong("\u0111ajevima", 338, 29),
    new SnowballAmong("ivima", -1, 79),
    new SnowballAmong("ovima", -1, 80),
    new SnowballAmong("govima", 346, 20),
    new SnowballAmong("ugovima", 347, 17),
    new SnowballAmong("lovima", 346, 82),
    new SnowballAmong("olovima", 349, 49),
    new SnowballAmong("movima", 346, 81),
    new SnowballAmong("onovima", 346, 12),
    new SnowballAmong("stvima", -1, 3),
    new SnowballAmong("\u0161tvima", -1, 4),
    new SnowballAmong("a\u0107ima", -1, 14),
    new SnowballAmong("e\u0107ima", -1, 15),
    new SnowballAmong("u\u0107ima", -1, 16),
    new SnowballAmong("ba\u0161ima", -1, 63),
    new SnowballAmong("ga\u0161ima", -1, 64),
    new SnowballAmong("ja\u0161ima", -1, 61),
    new SnowballAmong("ka\u0161ima", -1, 62),
    new SnowballAmong("na\u0161ima", -1, 60),
    new SnowballAmong("ta\u0161ima", -1, 59),
    new SnowballAmong("va\u0161ima", -1, 65),
    new SnowballAmong("e\u0161ima", -1, 66),
    new SnowballAmong("i\u0161ima", -1, 67),
    new SnowballAmong("o\u0161ima", -1, 91),
    new SnowballAmong("na", -1, 13),
    new SnowballAmong("ana", 368, 10),
    new SnowballAmong("acana", 369, 128),
    new SnowballAmong("urana", 369, 105),
    new SnowballAmong("tana", 369, 113),
    new SnowballAmong("avana", 369, 97),
    new SnowballAmong("evana", 369, 96),
    new SnowballAmong("ivana", 369, 98),
    new SnowballAmong("uvana", 369, 99),
    new SnowballAmong("a\u010Dana", 369, 102),
    new SnowballAmong("acena", 368, 124),
    new SnowballAmong("lucena", 368, 121),
    new SnowballAmong("a\u010Dena", 368, 101),
    new SnowballAmong("lu\u010Dena", 368, 117),
    new SnowballAmong("ina", 368, 11),
    new SnowballAmong("cina", 382, 137),
    new SnowballAmong("anina", 382, 10),
    new SnowballAmong("\u010Dina", 382, 89),
    new SnowballAmong("ona", 368, 12),
    new SnowballAmong("ara", -1, 53),
    new SnowballAmong("dra", -1, 54),
    new SnowballAmong("era", -1, 55),
    new SnowballAmong("ora", -1, 56),
    new SnowballAmong("basa", -1, 135),
    new SnowballAmong("gasa", -1, 131),
    new SnowballAmong("jasa", -1, 129),
    new SnowballAmong("kasa", -1, 133),
    new SnowballAmong("nasa", -1, 132),
    new SnowballAmong("tasa", -1, 130),
    new SnowballAmong("vasa", -1, 134),
    new SnowballAmong("esa", -1, 57),
    new SnowballAmong("isa", -1, 58),
    new SnowballAmong("osa", -1, 123),
    new SnowballAmong("ata", -1, 120),
    new SnowballAmong("ikata", 401, 68),
    new SnowballAmong("lata", 401, 69),
    new SnowballAmong("eta", -1, 70),
    new SnowballAmong("evita", -1, 92),
    new SnowballAmong("ovita", -1, 93),
    new SnowballAmong("asta", -1, 94),
    new SnowballAmong("esta", -1, 71),
    new SnowballAmong("ista", -1, 72),
    new SnowballAmong("ksta", -1, 73),
    new SnowballAmong("osta", -1, 74),
    new SnowballAmong("nuta", -1, 13),
    new SnowballAmong("i\u0161ta", -1, 75),
    new SnowballAmong("ava", -1, 77),
    new SnowballAmong("eva", -1, 78),
    new SnowballAmong("ajeva", 415, 109),
    new SnowballAmong("cajeva", 416, 26),
    new SnowballAmong("lajeva", 416, 30),
    new SnowballAmong("rajeva", 416, 31),
    new SnowballAmong("\u0107ajeva", 416, 28),
    new SnowballAmong("\u010Dajeva", 416, 27),
    new SnowballAmong("\u0111ajeva", 416, 29),
    new SnowballAmong("iva", -1, 79),
    new SnowballAmong("ova", -1, 80),
    new SnowballAmong("gova", 424, 20),
    new SnowballAmong("ugova", 425, 17),
    new SnowballAmong("lova", 424, 82),
    new SnowballAmong("olova", 427, 49),
    new SnowballAmong("mova", 424, 81),
    new SnowballAmong("onova", 424, 12),
    new SnowballAmong("stva", -1, 3),
    new SnowballAmong("\u0161tva", -1, 4),
    new SnowballAmong("a\u0107a", -1, 14),
    new SnowballAmong("e\u0107a", -1, 15),
    new SnowballAmong("u\u0107a", -1, 16),
    new SnowballAmong("ba\u0161a", -1, 63),
    new SnowballAmong("ga\u0161a", -1, 64),
    new SnowballAmong("ja\u0161a", -1, 61),
    new SnowballAmong("ka\u0161a", -1, 62),
    new SnowballAmong("na\u0161a", -1, 60),
    new SnowballAmong("ta\u0161a", -1, 59),
    new SnowballAmong("va\u0161a", -1, 65),
    new SnowballAmong("e\u0161a", -1, 66),
    new SnowballAmong("i\u0161a", -1, 67),
    new SnowballAmong("o\u0161a", -1, 91),
    new SnowballAmong("ace", -1, 124),
    new SnowballAmong("ece", -1, 125),
    new SnowballAmong("uce", -1, 126),
    new SnowballAmong("luce", 448, 121),
    new SnowballAmong("astade", -1, 110),
    new SnowballAmong("istade", -1, 111),
    new SnowballAmong("ostade", -1, 112),
    new SnowballAmong("ge", -1, 20),
    new SnowballAmong("loge", 453, 19),
    new SnowballAmong("uge", 453, 18),
    new SnowballAmong("aje", -1, 104),
    new SnowballAmong("caje", 456, 26),
    new SnowballAmong("laje", 456, 30),
    new SnowballAmong("raje", 456, 31),
    new SnowballAmong("astaje", 456, 106),
    new SnowballAmong("istaje", 456, 107),
    new SnowballAmong("ostaje", 456, 108),
    new SnowballAmong("\u0107aje", 456, 28),
    new SnowballAmong("\u010Daje", 456, 27),
    new SnowballAmong("\u0111aje", 456, 29),
    new SnowballAmong("ije", -1, 116),
    new SnowballAmong("bije", 466, 32),
    new SnowballAmong("cije", 466, 33),
    new SnowballAmong("dije", 466, 34),
    new SnowballAmong("fije", 466, 40),
    new SnowballAmong("gije", 466, 39),
    new SnowballAmong("anjije", 466, 84),
    new SnowballAmong("enjije", 466, 85),
    new SnowballAmong("snjije", 466, 122),
    new SnowballAmong("\u0161njije", 466, 86),
    new SnowballAmong("kije", 466, 95),
    new SnowballAmong("skije", 476, 1),
    new SnowballAmong("\u0161kije", 476, 2),
    new SnowballAmong("lije", 466, 35),
    new SnowballAmong("elije", 479, 83),
    new SnowballAmong("mije", 466, 37),
    new SnowballAmong("nije", 466, 13),
    new SnowballAmong("ganije", 482, 9),
    new SnowballAmong("manije", 482, 6),
    new SnowballAmong("panije", 482, 7),
    new SnowballAmong("ranije", 482, 8),
    new SnowballAmong("tanije", 482, 5),
    new SnowballAmong("pije", 466, 41),
    new SnowballAmong("rije", 466, 42),
    new SnowballAmong("sije", 466, 43),
    new SnowballAmong("osije", 490, 123),
    new SnowballAmong("tije", 466, 44),
    new SnowballAmong("atije", 492, 120),
    new SnowballAmong("evitije", 492, 92),
    new SnowballAmong("ovitije", 492, 93),
    new SnowballAmong("astije", 492, 94),
    new SnowballAmong("avije", 466, 77),
    new SnowballAmong("evije", 466, 78),
    new SnowballAmong("ivije", 466, 79),
    new SnowballAmong("ovije", 466, 80),
    new SnowballAmong("zije", 466, 45),
    new SnowballAmong("o\u0161ije", 466, 91),
    new SnowballAmong("\u017Eije", 466, 38),
    new SnowballAmong("anje", -1, 84),
    new SnowballAmong("enje", -1, 85),
    new SnowballAmong("snje", -1, 122),
    new SnowballAmong("\u0161nje", -1, 86),
    new SnowballAmong("uje", -1, 25),
    new SnowballAmong("lucuje", 508, 121),
    new SnowballAmong("iruje", 508, 100),
    new SnowballAmong("lu\u010Duje", 508, 117),
    new SnowballAmong("ke", -1, 95),
    new SnowballAmong("ske", 512, 1),
    new SnowballAmong("\u0161ke", 512, 2),
    new SnowballAmong("ale", -1, 104),
    new SnowballAmong("acale", 515, 128),
    new SnowballAmong("astajale", 515, 106),
    new SnowballAmong("istajale", 515, 107),
    new SnowballAmong("ostajale", 515, 108),
    new SnowballAmong("ijale", 515, 47),
    new SnowballAmong("injale", 515, 114),
    new SnowballAmong("nale", 515, 46),
    new SnowballAmong("irale", 515, 100),
    new SnowballAmong("urale", 515, 105),
    new SnowballAmong("tale", 515, 113),
    new SnowballAmong("astale", 525, 110),
    new SnowballAmong("istale", 525, 111),
    new SnowballAmong("ostale", 525, 112),
    new SnowballAmong("avale", 515, 97),
    new SnowballAmong("evale", 515, 96),
    new SnowballAmong("ivale", 515, 98),
    new SnowballAmong("ovale", 515, 76),
    new SnowballAmong("uvale", 515, 99),
    new SnowballAmong("a\u010Dale", 515, 102),
    new SnowballAmong("ele", -1, 83),
    new SnowballAmong("ile", -1, 116),
    new SnowballAmong("acile", 536, 124),
    new SnowballAmong("lucile", 536, 121),
    new SnowballAmong("nile", 536, 103),
    new SnowballAmong("rosile", 536, 127),
    new SnowballAmong("jetile", 536, 118),
    new SnowballAmong("ozile", 536, 48),
    new SnowballAmong("a\u010Dile", 536, 101),
    new SnowballAmong("lu\u010Dile", 536, 117),
    new SnowballAmong("ro\u0161ile", 536, 90),
    new SnowballAmong("ole", -1, 50),
    new SnowballAmong("asle", -1, 115),
    new SnowballAmong("nule", -1, 13),
    new SnowballAmong("rame", -1, 52),
    new SnowballAmong("leme", -1, 51),
    new SnowballAmong("acome", -1, 124),
    new SnowballAmong("ecome", -1, 125),
    new SnowballAmong("ucome", -1, 126),
    new SnowballAmong("anjome", -1, 84),
    new SnowballAmong("enjome", -1, 85),
    new SnowballAmong("snjome", -1, 122),
    new SnowballAmong("\u0161njome", -1, 86),
    new SnowballAmong("kome", -1, 95),
    new SnowballAmong("skome", 558, 1),
    new SnowballAmong("\u0161kome", 558, 2),
    new SnowballAmong("elome", -1, 83),
    new SnowballAmong("nome", -1, 13),
    new SnowballAmong("cinome", 562, 137),
    new SnowballAmong("\u010Dinome", 562, 89),
    new SnowballAmong("osome", -1, 123),
    new SnowballAmong("atome", -1, 120),
    new SnowballAmong("evitome", -1, 92),
    new SnowballAmong("ovitome", -1, 93),
    new SnowballAmong("astome", -1, 94),
    new SnowballAmong("avome", -1, 77),
    new SnowballAmong("evome", -1, 78),
    new SnowballAmong("ivome", -1, 79),
    new SnowballAmong("ovome", -1, 80),
    new SnowballAmong("a\u0107ome", -1, 14),
    new SnowballAmong("e\u0107ome", -1, 15),
    new SnowballAmong("u\u0107ome", -1, 16),
    new SnowballAmong("o\u0161ome", -1, 91),
    new SnowballAmong("ne", -1, 13),
    new SnowballAmong("ane", 578, 10),
    new SnowballAmong("acane", 579, 128),
    new SnowballAmong("urane", 579, 105),
    new SnowballAmong("tane", 579, 113),
    new SnowballAmong("astane", 582, 110),
    new SnowballAmong("istane", 582, 111),
    new SnowballAmong("ostane", 582, 112),
    new SnowballAmong("avane", 579, 97),
    new SnowballAmong("evane", 579, 96),
    new SnowballAmong("ivane", 579, 98),
    new SnowballAmong("uvane", 579, 99),
    new SnowballAmong("a\u010Dane", 579, 102),
    new SnowballAmong("acene", 578, 124),
    new SnowballAmong("lucene", 578, 121),
    new SnowballAmong("a\u010Dene", 578, 101),
    new SnowballAmong("lu\u010Dene", 578, 117),
    new SnowballAmong("ine", 578, 11),
    new SnowballAmong("cine", 595, 137),
    new SnowballAmong("anine", 595, 10),
    new SnowballAmong("\u010Dine", 595, 89),
    new SnowballAmong("one", 578, 12),
    new SnowballAmong("are", -1, 53),
    new SnowballAmong("dre", -1, 54),
    new SnowballAmong("ere", -1, 55),
    new SnowballAmong("ore", -1, 56),
    new SnowballAmong("ase", -1, 161),
    new SnowballAmong("base", 604, 135),
    new SnowballAmong("acase", 604, 128),
    new SnowballAmong("gase", 604, 131),
    new SnowballAmong("jase", 604, 129),
    new SnowballAmong("astajase", 608, 138),
    new SnowballAmong("istajase", 608, 139),
    new SnowballAmong("ostajase", 608, 140),
    new SnowballAmong("injase", 608, 150),
    new SnowballAmong("kase", 604, 133),
    new SnowballAmong("nase", 604, 132),
    new SnowballAmong("irase", 604, 155),
    new SnowballAmong("urase", 604, 156),
    new SnowballAmong("tase", 604, 130),
    new SnowballAmong("vase", 604, 134),
    new SnowballAmong("avase", 618, 144),
    new SnowballAmong("evase", 618, 145),
    new SnowballAmong("ivase", 618, 146),
    new SnowballAmong("ovase", 618, 148),
    new SnowballAmong("uvase", 618, 147),
    new SnowballAmong("ese", -1, 57),
    new SnowballAmong("ise", -1, 58),
    new SnowballAmong("acise", 625, 124),
    new SnowballAmong("lucise", 625, 121),
    new SnowballAmong("rosise", 625, 127),
    new SnowballAmong("jetise", 625, 149),
    new SnowballAmong("ose", -1, 123),
    new SnowballAmong("astadose", 630, 141),
    new SnowballAmong("istadose", 630, 142),
    new SnowballAmong("ostadose", 630, 143),
    new SnowballAmong("ate", -1, 104),
    new SnowballAmong("acate", 634, 128),
    new SnowballAmong("ikate", 634, 68),
    new SnowballAmong("late", 634, 69),
    new SnowballAmong("irate", 634, 100),
    new SnowballAmong("urate", 634, 105),
    new SnowballAmong("tate", 634, 113),
    new SnowballAmong("avate", 634, 97),
    new SnowballAmong("evate", 634, 96),
    new SnowballAmong("ivate", 634, 98),
    new SnowballAmong("uvate", 634, 99),
    new SnowballAmong("a\u010Date", 634, 102),
    new SnowballAmong("ete", -1, 70),
    new SnowballAmong("astadete", 646, 110),
    new SnowballAmong("istadete", 646, 111),
    new SnowballAmong("ostadete", 646, 112),
    new SnowballAmong("astajete", 646, 106),
    new SnowballAmong("istajete", 646, 107),
    new SnowballAmong("ostajete", 646, 108),
    new SnowballAmong("ijete", 646, 116),
    new SnowballAmong("injete", 646, 114),
    new SnowballAmong("ujete", 646, 25),
    new SnowballAmong("lucujete", 655, 121),
    new SnowballAmong("irujete", 655, 100),
    new SnowballAmong("lu\u010Dujete", 655, 117),
    new SnowballAmong("nete", 646, 13),
    new SnowballAmong("astanete", 659, 110),
    new SnowballAmong("istanete", 659, 111),
    new SnowballAmong("ostanete", 659, 112),
    new SnowballAmong("astete", 646, 115),
    new SnowballAmong("ite", -1, 116),
    new SnowballAmong("acite", 664, 124),
    new SnowballAmong("lucite", 664, 121),
    new SnowballAmong("nite", 664, 13),
    new SnowballAmong("astanite", 667, 110),
    new SnowballAmong("istanite", 667, 111),
    new SnowballAmong("ostanite", 667, 112),
    new SnowballAmong("rosite", 664, 127),
    new SnowballAmong("jetite", 664, 118),
    new SnowballAmong("astite", 664, 115),
    new SnowballAmong("evite", 664, 92),
    new SnowballAmong("ovite", 664, 93),
    new SnowballAmong("a\u010Dite", 664, 101),
    new SnowballAmong("lu\u010Dite", 664, 117),
    new SnowballAmong("ro\u0161ite", 664, 90),
    new SnowballAmong("ajte", -1, 104),
    new SnowballAmong("urajte", 679, 105),
    new SnowballAmong("tajte", 679, 113),
    new SnowballAmong("astajte", 681, 106),
    new SnowballAmong("istajte", 681, 107),
    new SnowballAmong("ostajte", 681, 108),
    new SnowballAmong("avajte", 679, 97),
    new SnowballAmong("evajte", 679, 96),
    new SnowballAmong("ivajte", 679, 98),
    new SnowballAmong("uvajte", 679, 99),
    new SnowballAmong("ijte", -1, 116),
    new SnowballAmong("lucujte", -1, 121),
    new SnowballAmong("irujte", -1, 100),
    new SnowballAmong("lu\u010Dujte", -1, 117),
    new SnowballAmong("aste", -1, 94),
    new SnowballAmong("acaste", 693, 128),
    new SnowballAmong("astajaste", 693, 106),
    new SnowballAmong("istajaste", 693, 107),
    new SnowballAmong("ostajaste", 693, 108),
    new SnowballAmong("injaste", 693, 114),
    new SnowballAmong("iraste", 693, 100),
    new SnowballAmong("uraste", 693, 105),
    new SnowballAmong("taste", 693, 113),
    new SnowballAmong("avaste", 693, 97),
    new SnowballAmong("evaste", 693, 96),
    new SnowballAmong("ivaste", 693, 98),
    new SnowballAmong("ovaste", 693, 76),
    new SnowballAmong("uvaste", 693, 99),
    new SnowballAmong("a\u010Daste", 693, 102),
    new SnowballAmong("este", -1, 71),
    new SnowballAmong("iste", -1, 72),
    new SnowballAmong("aciste", 709, 124),
    new SnowballAmong("luciste", 709, 121),
    new SnowballAmong("niste", 709, 103),
    new SnowballAmong("rosiste", 709, 127),
    new SnowballAmong("jetiste", 709, 118),
    new SnowballAmong("a\u010Diste", 709, 101),
    new SnowballAmong("lu\u010Diste", 709, 117),
    new SnowballAmong("ro\u0161iste", 709, 90),
    new SnowballAmong("kste", -1, 73),
    new SnowballAmong("oste", -1, 74),
    new SnowballAmong("astadoste", 719, 110),
    new SnowballAmong("istadoste", 719, 111),
    new SnowballAmong("ostadoste", 719, 112),
    new SnowballAmong("nuste", -1, 13),
    new SnowballAmong("i\u0161te", -1, 75),
    new SnowballAmong("ave", -1, 77),
    new SnowballAmong("eve", -1, 78),
    new SnowballAmong("ajeve", 726, 109),
    new SnowballAmong("cajeve", 727, 26),
    new SnowballAmong("lajeve", 727, 30),
    new SnowballAmong("rajeve", 727, 31),
    new SnowballAmong("\u0107ajeve", 727, 28),
    new SnowballAmong("\u010Dajeve", 727, 27),
    new SnowballAmong("\u0111ajeve", 727, 29),
    new SnowballAmong("ive", -1, 79),
    new SnowballAmong("ove", -1, 80),
    new SnowballAmong("gove", 735, 20),
    new SnowballAmong("ugove", 736, 17),
    new SnowballAmong("love", 735, 82),
    new SnowballAmong("olove", 738, 49),
    new SnowballAmong("move", 735, 81),
    new SnowballAmong("onove", 735, 12),
    new SnowballAmong("a\u0107e", -1, 14),
    new SnowballAmong("e\u0107e", -1, 15),
    new SnowballAmong("u\u0107e", -1, 16),
    new SnowballAmong("a\u010De", -1, 101),
    new SnowballAmong("lu\u010De", -1, 117),
    new SnowballAmong("a\u0161e", -1, 104),
    new SnowballAmong("ba\u0161e", 747, 63),
    new SnowballAmong("ga\u0161e", 747, 64),
    new SnowballAmong("ja\u0161e", 747, 61),
    new SnowballAmong("astaja\u0161e", 750, 106),
    new SnowballAmong("istaja\u0161e", 750, 107),
    new SnowballAmong("ostaja\u0161e", 750, 108),
    new SnowballAmong("inja\u0161e", 750, 114),
    new SnowballAmong("ka\u0161e", 747, 62),
    new SnowballAmong("na\u0161e", 747, 60),
    new SnowballAmong("ira\u0161e", 747, 100),
    new SnowballAmong("ura\u0161e", 747, 105),
    new SnowballAmong("ta\u0161e", 747, 59),
    new SnowballAmong("va\u0161e", 747, 65),
    new SnowballAmong("ava\u0161e", 760, 97),
    new SnowballAmong("eva\u0161e", 760, 96),
    new SnowballAmong("iva\u0161e", 760, 98),
    new SnowballAmong("ova\u0161e", 760, 76),
    new SnowballAmong("uva\u0161e", 760, 99),
    new SnowballAmong("a\u010Da\u0161e", 747, 102),
    new SnowballAmong("e\u0161e", -1, 66),
    new SnowballAmong("i\u0161e", -1, 67),
    new SnowballAmong("jeti\u0161e", 768, 118),
    new SnowballAmong("a\u010Di\u0161e", 768, 101),
    new SnowballAmong("lu\u010Di\u0161e", 768, 117),
    new SnowballAmong("ro\u0161i\u0161e", 768, 90),
    new SnowballAmong("o\u0161e", -1, 91),
    new SnowballAmong("astado\u0161e", 773, 110),
    new SnowballAmong("istado\u0161e", 773, 111),
    new SnowballAmong("ostado\u0161e", 773, 112),
    new SnowballAmong("aceg", -1, 124),
    new SnowballAmong("eceg", -1, 125),
    new SnowballAmong("uceg", -1, 126),
    new SnowballAmong("anjijeg", -1, 84),
    new SnowballAmong("enjijeg", -1, 85),
    new SnowballAmong("snjijeg", -1, 122),
    new SnowballAmong("\u0161njijeg", -1, 86),
    new SnowballAmong("kijeg", -1, 95),
    new SnowballAmong("skijeg", 784, 1),
    new SnowballAmong("\u0161kijeg", 784, 2),
    new SnowballAmong("elijeg", -1, 83),
    new SnowballAmong("nijeg", -1, 13),
    new SnowballAmong("osijeg", -1, 123),
    new SnowballAmong("atijeg", -1, 120),
    new SnowballAmong("evitijeg", -1, 92),
    new SnowballAmong("ovitijeg", -1, 93),
    new SnowballAmong("astijeg", -1, 94),
    new SnowballAmong("avijeg", -1, 77),
    new SnowballAmong("evijeg", -1, 78),
    new SnowballAmong("ivijeg", -1, 79),
    new SnowballAmong("ovijeg", -1, 80),
    new SnowballAmong("o\u0161ijeg", -1, 91),
    new SnowballAmong("anjeg", -1, 84),
    new SnowballAmong("enjeg", -1, 85),
    new SnowballAmong("snjeg", -1, 122),
    new SnowballAmong("\u0161njeg", -1, 86),
    new SnowballAmong("keg", -1, 95),
    new SnowballAmong("eleg", -1, 83),
    new SnowballAmong("neg", -1, 13),
    new SnowballAmong("aneg", 805, 10),
    new SnowballAmong("eneg", 805, 87),
    new SnowballAmong("sneg", 805, 159),
    new SnowballAmong("\u0161neg", 805, 88),
    new SnowballAmong("oseg", -1, 123),
    new SnowballAmong("ateg", -1, 120),
    new SnowballAmong("aveg", -1, 77),
    new SnowballAmong("eveg", -1, 78),
    new SnowballAmong("iveg", -1, 79),
    new SnowballAmong("oveg", -1, 80),
    new SnowballAmong("a\u0107eg", -1, 14),
    new SnowballAmong("e\u0107eg", -1, 15),
    new SnowballAmong("u\u0107eg", -1, 16),
    new SnowballAmong("o\u0161eg", -1, 91),
    new SnowballAmong("acog", -1, 124),
    new SnowballAmong("ecog", -1, 125),
    new SnowballAmong("ucog", -1, 126),
    new SnowballAmong("anjog", -1, 84),
    new SnowballAmong("enjog", -1, 85),
    new SnowballAmong("snjog", -1, 122),
    new SnowballAmong("\u0161njog", -1, 86),
    new SnowballAmong("kog", -1, 95),
    new SnowballAmong("skog", 827, 1),
    new SnowballAmong("\u0161kog", 827, 2),
    new SnowballAmong("elog", -1, 83),
    new SnowballAmong("nog", -1, 13),
    new SnowballAmong("cinog", 831, 137),
    new SnowballAmong("\u010Dinog", 831, 89),
    new SnowballAmong("osog", -1, 123),
    new SnowballAmong("atog", -1, 120),
    new SnowballAmong("evitog", -1, 92),
    new SnowballAmong("ovitog", -1, 93),
    new SnowballAmong("astog", -1, 94),
    new SnowballAmong("avog", -1, 77),
    new SnowballAmong("evog", -1, 78),
    new SnowballAmong("ivog", -1, 79),
    new SnowballAmong("ovog", -1, 80),
    new SnowballAmong("a\u0107og", -1, 14),
    new SnowballAmong("e\u0107og", -1, 15),
    new SnowballAmong("u\u0107og", -1, 16),
    new SnowballAmong("o\u0161og", -1, 91),
    new SnowballAmong("ah", -1, 104),
    new SnowballAmong("acah", 847, 128),
    new SnowballAmong("astajah", 847, 106),
    new SnowballAmong("istajah", 847, 107),
    new SnowballAmong("ostajah", 847, 108),
    new SnowballAmong("injah", 847, 114),
    new SnowballAmong("irah", 847, 100),
    new SnowballAmong("urah", 847, 105),
    new SnowballAmong("tah", 847, 113),
    new SnowballAmong("avah", 847, 97),
    new SnowballAmong("evah", 847, 96),
    new SnowballAmong("ivah", 847, 98),
    new SnowballAmong("ovah", 847, 76),
    new SnowballAmong("uvah", 847, 99),
    new SnowballAmong("a\u010Dah", 847, 102),
    new SnowballAmong("ih", -1, 116),
    new SnowballAmong("acih", 862, 124),
    new SnowballAmong("ecih", 862, 125),
    new SnowballAmong("ucih", 862, 126),
    new SnowballAmong("lucih", 865, 121),
    new SnowballAmong("anjijih", 862, 84),
    new SnowballAmong("enjijih", 862, 85),
    new SnowballAmong("snjijih", 862, 122),
    new SnowballAmong("\u0161njijih", 862, 86),
    new SnowballAmong("kijih", 862, 95),
    new SnowballAmong("skijih", 871, 1),
    new SnowballAmong("\u0161kijih", 871, 2),
    new SnowballAmong("elijih", 862, 83),
    new SnowballAmong("nijih", 862, 13),
    new SnowballAmong("osijih", 862, 123),
    new SnowballAmong("atijih", 862, 120),
    new SnowballAmong("evitijih", 862, 92),
    new SnowballAmong("ovitijih", 862, 93),
    new SnowballAmong("astijih", 862, 94),
    new SnowballAmong("avijih", 862, 77),
    new SnowballAmong("evijih", 862, 78),
    new SnowballAmong("ivijih", 862, 79),
    new SnowballAmong("ovijih", 862, 80),
    new SnowballAmong("o\u0161ijih", 862, 91),
    new SnowballAmong("anjih", 862, 84),
    new SnowballAmong("enjih", 862, 85),
    new SnowballAmong("snjih", 862, 122),
    new SnowballAmong("\u0161njih", 862, 86),
    new SnowballAmong("kih", 862, 95),
    new SnowballAmong("skih", 890, 1),
    new SnowballAmong("\u0161kih", 890, 2),
    new SnowballAmong("elih", 862, 83),
    new SnowballAmong("nih", 862, 13),
    new SnowballAmong("cinih", 894, 137),
    new SnowballAmong("\u010Dinih", 894, 89),
    new SnowballAmong("osih", 862, 123),
    new SnowballAmong("rosih", 897, 127),
    new SnowballAmong("atih", 862, 120),
    new SnowballAmong("jetih", 862, 118),
    new SnowballAmong("evitih", 862, 92),
    new SnowballAmong("ovitih", 862, 93),
    new SnowballAmong("astih", 862, 94),
    new SnowballAmong("avih", 862, 77),
    new SnowballAmong("evih", 862, 78),
    new SnowballAmong("ivih", 862, 79),
    new SnowballAmong("ovih", 862, 80),
    new SnowballAmong("a\u0107ih", 862, 14),
    new SnowballAmong("e\u0107ih", 862, 15),
    new SnowballAmong("u\u0107ih", 862, 16),
    new SnowballAmong("a\u010Dih", 862, 101),
    new SnowballAmong("lu\u010Dih", 862, 117),
    new SnowballAmong("o\u0161ih", 862, 91),
    new SnowballAmong("ro\u0161ih", 913, 90),
    new SnowballAmong("astadoh", -1, 110),
    new SnowballAmong("istadoh", -1, 111),
    new SnowballAmong("ostadoh", -1, 112),
    new SnowballAmong("acuh", -1, 124),
    new SnowballAmong("ecuh", -1, 125),
    new SnowballAmong("ucuh", -1, 126),
    new SnowballAmong("a\u0107uh", -1, 14),
    new SnowballAmong("e\u0107uh", -1, 15),
    new SnowballAmong("u\u0107uh", -1, 16),
    new SnowballAmong("aci", -1, 124),
    new SnowballAmong("aceci", -1, 124),
    new SnowballAmong("ieci", -1, 162),
    new SnowballAmong("ajuci", -1, 161),
    new SnowballAmong("irajuci", 927, 155),
    new SnowballAmong("urajuci", 927, 156),
    new SnowballAmong("astajuci", 927, 138),
    new SnowballAmong("istajuci", 927, 139),
    new SnowballAmong("ostajuci", 927, 140),
    new SnowballAmong("avajuci", 927, 144),
    new SnowballAmong("evajuci", 927, 145),
    new SnowballAmong("ivajuci", 927, 146),
    new SnowballAmong("uvajuci", 927, 147),
    new SnowballAmong("ujuci", -1, 157),
    new SnowballAmong("lucujuci", 937, 121),
    new SnowballAmong("irujuci", 937, 155),
    new SnowballAmong("luci", -1, 121),
    new SnowballAmong("nuci", -1, 164),
    new SnowballAmong("etuci", -1, 153),
    new SnowballAmong("astuci", -1, 136),
    new SnowballAmong("gi", -1, 20),
    new SnowballAmong("ugi", 944, 18),
    new SnowballAmong("aji", -1, 109),
    new SnowballAmong("caji", 946, 26),
    new SnowballAmong("laji", 946, 30),
    new SnowballAmong("raji", 946, 31),
    new SnowballAmong("\u0107aji", 946, 28),
    new SnowballAmong("\u010Daji", 946, 27),
    new SnowballAmong("\u0111aji", 946, 29),
    new SnowballAmong("biji", -1, 32),
    new SnowballAmong("ciji", -1, 33),
    new SnowballAmong("diji", -1, 34),
    new SnowballAmong("fiji", -1, 40),
    new SnowballAmong("giji", -1, 39),
    new SnowballAmong("anjiji", -1, 84),
    new SnowballAmong("enjiji", -1, 85),
    new SnowballAmong("snjiji", -1, 122),
    new SnowballAmong("\u0161njiji", -1, 86),
    new SnowballAmong("kiji", -1, 95),
    new SnowballAmong("skiji", 962, 1),
    new SnowballAmong("\u0161kiji", 962, 2),
    new SnowballAmong("liji", -1, 35),
    new SnowballAmong("eliji", 965, 83),
    new SnowballAmong("miji", -1, 37),
    new SnowballAmong("niji", -1, 13),
    new SnowballAmong("ganiji", 968, 9),
    new SnowballAmong("maniji", 968, 6),
    new SnowballAmong("paniji", 968, 7),
    new SnowballAmong("raniji", 968, 8),
    new SnowballAmong("taniji", 968, 5),
    new SnowballAmong("piji", -1, 41),
    new SnowballAmong("riji", -1, 42),
    new SnowballAmong("siji", -1, 43),
    new SnowballAmong("osiji", 976, 123),
    new SnowballAmong("tiji", -1, 44),
    new SnowballAmong("atiji", 978, 120),
    new SnowballAmong("evitiji", 978, 92),
    new SnowballAmong("ovitiji", 978, 93),
    new SnowballAmong("astiji", 978, 94),
    new SnowballAmong("aviji", -1, 77),
    new SnowballAmong("eviji", -1, 78),
    new SnowballAmong("iviji", -1, 79),
    new SnowballAmong("oviji", -1, 80),
    new SnowballAmong("ziji", -1, 45),
    new SnowballAmong("o\u0161iji", -1, 91),
    new SnowballAmong("\u017Eiji", -1, 38),
    new SnowballAmong("anji", -1, 84),
    new SnowballAmong("enji", -1, 85),
    new SnowballAmong("snji", -1, 122),
    new SnowballAmong("\u0161nji", -1, 86),
    new SnowballAmong("ki", -1, 95),
    new SnowballAmong("ski", 994, 1),
    new SnowballAmong("\u0161ki", 994, 2),
    new SnowballAmong("ali", -1, 104),
    new SnowballAmong("acali", 997, 128),
    new SnowballAmong("astajali", 997, 106),
    new SnowballAmong("istajali", 997, 107),
    new SnowballAmong("ostajali", 997, 108),
    new SnowballAmong("ijali", 997, 47),
    new SnowballAmong("injali", 997, 114),
    new SnowballAmong("nali", 997, 46),
    new SnowballAmong("irali", 997, 100),
    new SnowballAmong("urali", 997, 105),
    new SnowballAmong("tali", 997, 113),
    new SnowballAmong("astali", 1007, 110),
    new SnowballAmong("istali", 1007, 111),
    new SnowballAmong("ostali", 1007, 112),
    new SnowballAmong("avali", 997, 97),
    new SnowballAmong("evali", 997, 96),
    new SnowballAmong("ivali", 997, 98),
    new SnowballAmong("ovali", 997, 76),
    new SnowballAmong("uvali", 997, 99),
    new SnowballAmong("a\u010Dali", 997, 102),
    new SnowballAmong("eli", -1, 83),
    new SnowballAmong("ili", -1, 116),
    new SnowballAmong("acili", 1018, 124),
    new SnowballAmong("lucili", 1018, 121),
    new SnowballAmong("nili", 1018, 103),
    new SnowballAmong("rosili", 1018, 127),
    new SnowballAmong("jetili", 1018, 118),
    new SnowballAmong("ozili", 1018, 48),
    new SnowballAmong("a\u010Dili", 1018, 101),
    new SnowballAmong("lu\u010Dili", 1018, 117),
    new SnowballAmong("ro\u0161ili", 1018, 90),
    new SnowballAmong("oli", -1, 50),
    new SnowballAmong("asli", -1, 115),
    new SnowballAmong("nuli", -1, 13),
    new SnowballAmong("rami", -1, 52),
    new SnowballAmong("lemi", -1, 51),
    new SnowballAmong("ni", -1, 13),
    new SnowballAmong("ani", 1033, 10),
    new SnowballAmong("acani", 1034, 128),
    new SnowballAmong("urani", 1034, 105),
    new SnowballAmong("tani", 1034, 113),
    new SnowballAmong("avani", 1034, 97),
    new SnowballAmong("evani", 1034, 96),
    new SnowballAmong("ivani", 1034, 98),
    new SnowballAmong("uvani", 1034, 99),
    new SnowballAmong("a\u010Dani", 1034, 102),
    new SnowballAmong("aceni", 1033, 124),
    new SnowballAmong("luceni", 1033, 121),
    new SnowballAmong("a\u010Deni", 1033, 101),
    new SnowballAmong("lu\u010Deni", 1033, 117),
    new SnowballAmong("ini", 1033, 11),
    new SnowballAmong("cini", 1047, 137),
    new SnowballAmong("\u010Dini", 1047, 89),
    new SnowballAmong("oni", 1033, 12),
    new SnowballAmong("ari", -1, 53),
    new SnowballAmong("dri", -1, 54),
    new SnowballAmong("eri", -1, 55),
    new SnowballAmong("ori", -1, 56),
    new SnowballAmong("basi", -1, 135),
    new SnowballAmong("gasi", -1, 131),
    new SnowballAmong("jasi", -1, 129),
    new SnowballAmong("kasi", -1, 133),
    new SnowballAmong("nasi", -1, 132),
    new SnowballAmong("tasi", -1, 130),
    new SnowballAmong("vasi", -1, 134),
    new SnowballAmong("esi", -1, 152),
    new SnowballAmong("isi", -1, 154),
    new SnowballAmong("osi", -1, 123),
    new SnowballAmong("avsi", -1, 161),
    new SnowballAmong("acavsi", 1065, 128),
    new SnowballAmong("iravsi", 1065, 155),
    new SnowballAmong("tavsi", 1065, 160),
    new SnowballAmong("etavsi", 1068, 153),
    new SnowballAmong("astavsi", 1068, 141),
    new SnowballAmong("istavsi", 1068, 142),
    new SnowballAmong("ostavsi", 1068, 143),
    new SnowballAmong("ivsi", -1, 162),
    new SnowballAmong("nivsi", 1073, 158),
    new SnowballAmong("rosivsi", 1073, 127),
    new SnowballAmong("nuvsi", -1, 164),
    new SnowballAmong("ati", -1, 104),
    new SnowballAmong("acati", 1077, 128),
    new SnowballAmong("astajati", 1077, 106),
    new SnowballAmong("istajati", 1077, 107),
    new SnowballAmong("ostajati", 1077, 108),
    new SnowballAmong("injati", 1077, 114),
    new SnowballAmong("ikati", 1077, 68),
    new SnowballAmong("lati", 1077, 69),
    new SnowballAmong("irati", 1077, 100),
    new SnowballAmong("urati", 1077, 105),
    new SnowballAmong("tati", 1077, 113),
    new SnowballAmong("astati", 1087, 110),
    new SnowballAmong("istati", 1087, 111),
    new SnowballAmong("ostati", 1087, 112),
    new SnowballAmong("avati", 1077, 97),
    new SnowballAmong("evati", 1077, 96),
    new SnowballAmong("ivati", 1077, 98),
    new SnowballAmong("ovati", 1077, 76),
    new SnowballAmong("uvati", 1077, 99),
    new SnowballAmong("a\u010Dati", 1077, 102),
    new SnowballAmong("eti", -1, 70),
    new SnowballAmong("iti", -1, 116),
    new SnowballAmong("aciti", 1098, 124),
    new SnowballAmong("luciti", 1098, 121),
    new SnowballAmong("niti", 1098, 103),
    new SnowballAmong("rositi", 1098, 127),
    new SnowballAmong("jetiti", 1098, 118),
    new SnowballAmong("eviti", 1098, 92),
    new SnowballAmong("oviti", 1098, 93),
    new SnowballAmong("a\u010Diti", 1098, 101),
    new SnowballAmong("lu\u010Diti", 1098, 117),
    new SnowballAmong("ro\u0161iti", 1098, 90),
    new SnowballAmong("asti", -1, 94),
    new SnowballAmong("esti", -1, 71),
    new SnowballAmong("isti", -1, 72),
    new SnowballAmong("ksti", -1, 73),
    new SnowballAmong("osti", -1, 74),
    new SnowballAmong("nuti", -1, 13),
    new SnowballAmong("avi", -1, 77),
    new SnowballAmong("evi", -1, 78),
    new SnowballAmong("ajevi", 1116, 109),
    new SnowballAmong("cajevi", 1117, 26),
    new SnowballAmong("lajevi", 1117, 30),
    new SnowballAmong("rajevi", 1117, 31),
    new SnowballAmong("\u0107ajevi", 1117, 28),
    new SnowballAmong("\u010Dajevi", 1117, 27),
    new SnowballAmong("\u0111ajevi", 1117, 29),
    new SnowballAmong("ivi", -1, 79),
    new SnowballAmong("ovi", -1, 80),
    new SnowballAmong("govi", 1125, 20),
    new SnowballAmong("ugovi", 1126, 17),
    new SnowballAmong("lovi", 1125, 82),
    new SnowballAmong("olovi", 1128, 49),
    new SnowballAmong("movi", 1125, 81),
    new SnowballAmong("onovi", 1125, 12),
    new SnowballAmong("ie\u0107i", -1, 116),
    new SnowballAmong("a\u010De\u0107i", -1, 101),
    new SnowballAmong("aju\u0107i", -1, 104),
    new SnowballAmong("iraju\u0107i", 1134, 100),
    new SnowballAmong("uraju\u0107i", 1134, 105),
    new SnowballAmong("astaju\u0107i", 1134, 106),
    new SnowballAmong("istaju\u0107i", 1134, 107),
    new SnowballAmong("ostaju\u0107i", 1134, 108),
    new SnowballAmong("avaju\u0107i", 1134, 97),
    new SnowballAmong("evaju\u0107i", 1134, 96),
    new SnowballAmong("ivaju\u0107i", 1134, 98),
    new SnowballAmong("uvaju\u0107i", 1134, 99),
    new SnowballAmong("uju\u0107i", -1, 25),
    new SnowballAmong("iruju\u0107i", 1144, 100),
    new SnowballAmong("lu\u010Duju\u0107i", 1144, 117),
    new SnowballAmong("nu\u0107i", -1, 13),
    new SnowballAmong("etu\u0107i", -1, 70),
    new SnowballAmong("astu\u0107i", -1, 115),
    new SnowballAmong("a\u010Di", -1, 101),
    new SnowballAmong("lu\u010Di", -1, 117),
    new SnowballAmong("ba\u0161i", -1, 63),
    new SnowballAmong("ga\u0161i", -1, 64),
    new SnowballAmong("ja\u0161i", -1, 61),
    new SnowballAmong("ka\u0161i", -1, 62),
    new SnowballAmong("na\u0161i", -1, 60),
    new SnowballAmong("ta\u0161i", -1, 59),
    new SnowballAmong("va\u0161i", -1, 65),
    new SnowballAmong("e\u0161i", -1, 66),
    new SnowballAmong("i\u0161i", -1, 67),
    new SnowballAmong("o\u0161i", -1, 91),
    new SnowballAmong("av\u0161i", -1, 104),
    new SnowballAmong("irav\u0161i", 1162, 100),
    new SnowballAmong("tav\u0161i", 1162, 113),
    new SnowballAmong("etav\u0161i", 1164, 70),
    new SnowballAmong("astav\u0161i", 1164, 110),
    new SnowballAmong("istav\u0161i", 1164, 111),
    new SnowballAmong("ostav\u0161i", 1164, 112),
    new SnowballAmong("a\u010Dav\u0161i", 1162, 102),
    new SnowballAmong("iv\u0161i", -1, 116),
    new SnowballAmong("niv\u0161i", 1170, 103),
    new SnowballAmong("ro\u0161iv\u0161i", 1170, 90),
    new SnowballAmong("nuv\u0161i", -1, 13),
    new SnowballAmong("aj", -1, 104),
    new SnowballAmong("uraj", 1174, 105),
    new SnowballAmong("taj", 1174, 113),
    new SnowballAmong("avaj", 1174, 97),
    new SnowballAmong("evaj", 1174, 96),
    new SnowballAmong("ivaj", 1174, 98),
    new SnowballAmong("uvaj", 1174, 99),
    new SnowballAmong("ij", -1, 116),
    new SnowballAmong("acoj", -1, 124),
    new SnowballAmong("ecoj", -1, 125),
    new SnowballAmong("ucoj", -1, 126),
    new SnowballAmong("anjijoj", -1, 84),
    new SnowballAmong("enjijoj", -1, 85),
    new SnowballAmong("snjijoj", -1, 122),
    new SnowballAmong("\u0161njijoj", -1, 86),
    new SnowballAmong("kijoj", -1, 95),
    new SnowballAmong("skijoj", 1189, 1),
    new SnowballAmong("\u0161kijoj", 1189, 2),
    new SnowballAmong("elijoj", -1, 83),
    new SnowballAmong("nijoj", -1, 13),
    new SnowballAmong("osijoj", -1, 123),
    new SnowballAmong("evitijoj", -1, 92),
    new SnowballAmong("ovitijoj", -1, 93),
    new SnowballAmong("astijoj", -1, 94),
    new SnowballAmong("avijoj", -1, 77),
    new SnowballAmong("evijoj", -1, 78),
    new SnowballAmong("ivijoj", -1, 79),
    new SnowballAmong("ovijoj", -1, 80),
    new SnowballAmong("o\u0161ijoj", -1, 91),
    new SnowballAmong("anjoj", -1, 84),
    new SnowballAmong("enjoj", -1, 85),
    new SnowballAmong("snjoj", -1, 122),
    new SnowballAmong("\u0161njoj", -1, 86),
    new SnowballAmong("koj", -1, 95),
    new SnowballAmong("skoj", 1207, 1),
    new SnowballAmong("\u0161koj", 1207, 2),
    new SnowballAmong("aloj", -1, 104),
    new SnowballAmong("eloj", -1, 83),
    new SnowballAmong("noj", -1, 13),
    new SnowballAmong("cinoj", 1212, 137),
    new SnowballAmong("\u010Dinoj", 1212, 89),
    new SnowballAmong("osoj", -1, 123),
    new SnowballAmong("atoj", -1, 120),
    new SnowballAmong("evitoj", -1, 92),
    new SnowballAmong("ovitoj", -1, 93),
    new SnowballAmong("astoj", -1, 94),
    new SnowballAmong("avoj", -1, 77),
    new SnowballAmong("evoj", -1, 78),
    new SnowballAmong("ivoj", -1, 79),
    new SnowballAmong("ovoj", -1, 80),
    new SnowballAmong("a\u0107oj", -1, 14),
    new SnowballAmong("e\u0107oj", -1, 15),
    new SnowballAmong("u\u0107oj", -1, 16),
    new SnowballAmong("o\u0161oj", -1, 91),
    new SnowballAmong("lucuj", -1, 121),
    new SnowballAmong("iruj", -1, 100),
    new SnowballAmong("lu\u010Duj", -1, 117),
    new SnowballAmong("al", -1, 104),
    new SnowballAmong("iral", 1231, 100),
    new SnowballAmong("ural", 1231, 105),
    new SnowballAmong("el", -1, 119),
    new SnowballAmong("il", -1, 116),
    new SnowballAmong("am", -1, 104),
    new SnowballAmong("acam", 1236, 128),
    new SnowballAmong("iram", 1236, 100),
    new SnowballAmong("uram", 1236, 105),
    new SnowballAmong("tam", 1236, 113),
    new SnowballAmong("avam", 1236, 97),
    new SnowballAmong("evam", 1236, 96),
    new SnowballAmong("ivam", 1236, 98),
    new SnowballAmong("uvam", 1236, 99),
    new SnowballAmong("a\u010Dam", 1236, 102),
    new SnowballAmong("em", -1, 119),
    new SnowballAmong("acem", 1246, 124),
    new SnowballAmong("ecem", 1246, 125),
    new SnowballAmong("ucem", 1246, 126),
    new SnowballAmong("astadem", 1246, 110),
    new SnowballAmong("istadem", 1246, 111),
    new SnowballAmong("ostadem", 1246, 112),
    new SnowballAmong("ajem", 1246, 104),
    new SnowballAmong("cajem", 1253, 26),
    new SnowballAmong("lajem", 1253, 30),
    new SnowballAmong("rajem", 1253, 31),
    new SnowballAmong("astajem", 1253, 106),
    new SnowballAmong("istajem", 1253, 107),
    new SnowballAmong("ostajem", 1253, 108),
    new SnowballAmong("\u0107ajem", 1253, 28),
    new SnowballAmong("\u010Dajem", 1253, 27),
    new SnowballAmong("\u0111ajem", 1253, 29),
    new SnowballAmong("ijem", 1246, 116),
    new SnowballAmong("anjijem", 1263, 84),
    new SnowballAmong("enjijem", 1263, 85),
    new SnowballAmong("snjijem", 1263, 123),
    new SnowballAmong("\u0161njijem", 1263, 86),
    new SnowballAmong("kijem", 1263, 95),
    new SnowballAmong("skijem", 1268, 1),
    new SnowballAmong("\u0161kijem", 1268, 2),
    new SnowballAmong("lijem", 1263, 24),
    new SnowballAmong("elijem", 1271, 83),
    new SnowballAmong("nijem", 1263, 13),
    new SnowballAmong("rarijem", 1263, 21),
    new SnowballAmong("sijem", 1263, 23),
    new SnowballAmong("osijem", 1275, 123),
    new SnowballAmong("atijem", 1263, 120),
    new SnowballAmong("evitijem", 1263, 92),
    new SnowballAmong("ovitijem", 1263, 93),
    new SnowballAmong("otijem", 1263, 22),
    new SnowballAmong("astijem", 1263, 94),
    new SnowballAmong("avijem", 1263, 77),
    new SnowballAmong("evijem", 1263, 78),
    new SnowballAmong("ivijem", 1263, 79),
    new SnowballAmong("ovijem", 1263, 80),
    new SnowballAmong("o\u0161ijem", 1263, 91),
    new SnowballAmong("anjem", 1246, 84),
    new SnowballAmong("enjem", 1246, 85),
    new SnowballAmong("injem", 1246, 114),
    new SnowballAmong("snjem", 1246, 122),
    new SnowballAmong("\u0161njem", 1246, 86),
    new SnowballAmong("ujem", 1246, 25),
    new SnowballAmong("lucujem", 1292, 121),
    new SnowballAmong("irujem", 1292, 100),
    new SnowballAmong("lu\u010Dujem", 1292, 117),
    new SnowballAmong("kem", 1246, 95),
    new SnowballAmong("skem", 1296, 1),
    new SnowballAmong("\u0161kem", 1296, 2),
    new SnowballAmong("elem", 1246, 83),
    new SnowballAmong("nem", 1246, 13),
    new SnowballAmong("anem", 1300, 10),
    new SnowballAmong("astanem", 1301, 110),
    new SnowballAmong("istanem", 1301, 111),
    new SnowballAmong("ostanem", 1301, 112),
    new SnowballAmong("enem", 1300, 87),
    new SnowballAmong("snem", 1300, 159),
    new SnowballAmong("\u0161nem", 1300, 88),
    new SnowballAmong("basem", 1246, 135),
    new SnowballAmong("gasem", 1246, 131),
    new SnowballAmong("jasem", 1246, 129),
    new SnowballAmong("kasem", 1246, 133),
    new SnowballAmong("nasem", 1246, 132),
    new SnowballAmong("tasem", 1246, 130),
    new SnowballAmong("vasem", 1246, 134),
    new SnowballAmong("esem", 1246, 152),
    new SnowballAmong("isem", 1246, 154),
    new SnowballAmong("osem", 1246, 123),
    new SnowballAmong("atem", 1246, 120),
    new SnowballAmong("etem", 1246, 70),
    new SnowballAmong("evitem", 1246, 92),
    new SnowballAmong("ovitem", 1246, 93),
    new SnowballAmong("astem", 1246, 94),
    new SnowballAmong("istem", 1246, 151),
    new SnowballAmong("i\u0161tem", 1246, 75),
    new SnowballAmong("avem", 1246, 77),
    new SnowballAmong("evem", 1246, 78),
    new SnowballAmong("ivem", 1246, 79),
    new SnowballAmong("a\u0107em", 1246, 14),
    new SnowballAmong("e\u0107em", 1246, 15),
    new SnowballAmong("u\u0107em", 1246, 16),
    new SnowballAmong("ba\u0161em", 1246, 63),
    new SnowballAmong("ga\u0161em", 1246, 64),
    new SnowballAmong("ja\u0161em", 1246, 61),
    new SnowballAmong("ka\u0161em", 1246, 62),
    new SnowballAmong("na\u0161em", 1246, 60),
    new SnowballAmong("ta\u0161em", 1246, 59),
    new SnowballAmong("va\u0161em", 1246, 65),
    new SnowballAmong("e\u0161em", 1246, 66),
    new SnowballAmong("i\u0161em", 1246, 67),
    new SnowballAmong("o\u0161em", 1246, 91),
    new SnowballAmong("im", -1, 116),
    new SnowballAmong("acim", 1341, 124),
    new SnowballAmong("ecim", 1341, 125),
    new SnowballAmong("ucim", 1341, 126),
    new SnowballAmong("lucim", 1344, 121),
    new SnowballAmong("anjijim", 1341, 84),
    new SnowballAmong("enjijim", 1341, 85),
    new SnowballAmong("snjijim", 1341, 122),
    new SnowballAmong("\u0161njijim", 1341, 86),
    new SnowballAmong("kijim", 1341, 95),
    new SnowballAmong("skijim", 1350, 1),
    new SnowballAmong("\u0161kijim", 1350, 2),
    new SnowballAmong("elijim", 1341, 83),
    new SnowballAmong("nijim", 1341, 13),
    new SnowballAmong("osijim", 1341, 123),
    new SnowballAmong("atijim", 1341, 120),
    new SnowballAmong("evitijim", 1341, 92),
    new SnowballAmong("ovitijim", 1341, 93),
    new SnowballAmong("astijim", 1341, 94),
    new SnowballAmong("avijim", 1341, 77),
    new SnowballAmong("evijim", 1341, 78),
    new SnowballAmong("ivijim", 1341, 79),
    new SnowballAmong("ovijim", 1341, 80),
    new SnowballAmong("o\u0161ijim", 1341, 91),
    new SnowballAmong("anjim", 1341, 84),
    new SnowballAmong("enjim", 1341, 85),
    new SnowballAmong("snjim", 1341, 122),
    new SnowballAmong("\u0161njim", 1341, 86),
    new SnowballAmong("kim", 1341, 95),
    new SnowballAmong("skim", 1369, 1),
    new SnowballAmong("\u0161kim", 1369, 2),
    new SnowballAmong("elim", 1341, 83),
    new SnowballAmong("nim", 1341, 13),
    new SnowballAmong("cinim", 1373, 137),
    new SnowballAmong("\u010Dinim", 1373, 89),
    new SnowballAmong("osim", 1341, 123),
    new SnowballAmong("rosim", 1376, 127),
    new SnowballAmong("atim", 1341, 120),
    new SnowballAmong("jetim", 1341, 118),
    new SnowballAmong("evitim", 1341, 92),
    new SnowballAmong("ovitim", 1341, 93),
    new SnowballAmong("astim", 1341, 94),
    new SnowballAmong("avim", 1341, 77),
    new SnowballAmong("evim", 1341, 78),
    new SnowballAmong("ivim", 1341, 79),
    new SnowballAmong("ovim", 1341, 80),
    new SnowballAmong("a\u0107im", 1341, 14),
    new SnowballAmong("e\u0107im", 1341, 15),
    new SnowballAmong("u\u0107im", 1341, 16),
    new SnowballAmong("a\u010Dim", 1341, 101),
    new SnowballAmong("lu\u010Dim", 1341, 117),
    new SnowballAmong("o\u0161im", 1341, 91),
    new SnowballAmong("ro\u0161im", 1392, 90),
    new SnowballAmong("acom", -1, 124),
    new SnowballAmong("ecom", -1, 125),
    new SnowballAmong("ucom", -1, 126),
    new SnowballAmong("gom", -1, 20),
    new SnowballAmong("logom", 1397, 19),
    new SnowballAmong("ugom", 1397, 18),
    new SnowballAmong("bijom", -1, 32),
    new SnowballAmong("cijom", -1, 33),
    new SnowballAmong("dijom", -1, 34),
    new SnowballAmong("fijom", -1, 40),
    new SnowballAmong("gijom", -1, 39),
    new SnowballAmong("lijom", -1, 35),
    new SnowballAmong("mijom", -1, 37),
    new SnowballAmong("nijom", -1, 36),
    new SnowballAmong("ganijom", 1407, 9),
    new SnowballAmong("manijom", 1407, 6),
    new SnowballAmong("panijom", 1407, 7),
    new SnowballAmong("ranijom", 1407, 8),
    new SnowballAmong("tanijom", 1407, 5),
    new SnowballAmong("pijom", -1, 41),
    new SnowballAmong("rijom", -1, 42),
    new SnowballAmong("sijom", -1, 43),
    new SnowballAmong("tijom", -1, 44),
    new SnowballAmong("zijom", -1, 45),
    new SnowballAmong("\u017Eijom", -1, 38),
    new SnowballAmong("anjom", -1, 84),
    new SnowballAmong("enjom", -1, 85),
    new SnowballAmong("snjom", -1, 122),
    new SnowballAmong("\u0161njom", -1, 86),
    new SnowballAmong("kom", -1, 95),
    new SnowballAmong("skom", 1423, 1),
    new SnowballAmong("\u0161kom", 1423, 2),
    new SnowballAmong("alom", -1, 104),
    new SnowballAmong("ijalom", 1426, 47),
    new SnowballAmong("nalom", 1426, 46),
    new SnowballAmong("elom", -1, 83),
    new SnowballAmong("ilom", -1, 116),
    new SnowballAmong("ozilom", 1430, 48),
    new SnowballAmong("olom", -1, 50),
    new SnowballAmong("ramom", -1, 52),
    new SnowballAmong("lemom", -1, 51),
    new SnowballAmong("nom", -1, 13),
    new SnowballAmong("anom", 1435, 10),
    new SnowballAmong("inom", 1435, 11),
    new SnowballAmong("cinom", 1437, 137),
    new SnowballAmong("aninom", 1437, 10),
    new SnowballAmong("\u010Dinom", 1437, 89),
    new SnowballAmong("onom", 1435, 12),
    new SnowballAmong("arom", -1, 53),
    new SnowballAmong("drom", -1, 54),
    new SnowballAmong("erom", -1, 55),
    new SnowballAmong("orom", -1, 56),
    new SnowballAmong("basom", -1, 135),
    new SnowballAmong("gasom", -1, 131),
    new SnowballAmong("jasom", -1, 129),
    new SnowballAmong("kasom", -1, 133),
    new SnowballAmong("nasom", -1, 132),
    new SnowballAmong("tasom", -1, 130),
    new SnowballAmong("vasom", -1, 134),
    new SnowballAmong("esom", -1, 57),
    new SnowballAmong("isom", -1, 58),
    new SnowballAmong("osom", -1, 123),
    new SnowballAmong("atom", -1, 120),
    new SnowballAmong("ikatom", 1456, 68),
    new SnowballAmong("latom", 1456, 69),
    new SnowballAmong("etom", -1, 70),
    new SnowballAmong("evitom", -1, 92),
    new SnowballAmong("ovitom", -1, 93),
    new SnowballAmong("astom", -1, 94),
    new SnowballAmong("estom", -1, 71),
    new SnowballAmong("istom", -1, 72),
    new SnowballAmong("kstom", -1, 73),
    new SnowballAmong("ostom", -1, 74),
    new SnowballAmong("avom", -1, 77),
    new SnowballAmong("evom", -1, 78),
    new SnowballAmong("ivom", -1, 79),
    new SnowballAmong("ovom", -1, 80),
    new SnowballAmong("lovom", 1470, 82),
    new SnowballAmong("movom", 1470, 81),
    new SnowballAmong("stvom", -1, 3),
    new SnowballAmong("\u0161tvom", -1, 4),
    new SnowballAmong("a\u0107om", -1, 14),
    new SnowballAmong("e\u0107om", -1, 15),
    new SnowballAmong("u\u0107om", -1, 16),
    new SnowballAmong("ba\u0161om", -1, 63),
    new SnowballAmong("ga\u0161om", -1, 64),
    new SnowballAmong("ja\u0161om", -1, 61),
    new SnowballAmong("ka\u0161om", -1, 62),
    new SnowballAmong("na\u0161om", -1, 60),
    new SnowballAmong("ta\u0161om", -1, 59),
    new SnowballAmong("va\u0161om", -1, 65),
    new SnowballAmong("e\u0161om", -1, 66),
    new SnowballAmong("i\u0161om", -1, 67),
    new SnowballAmong("o\u0161om", -1, 91),
    new SnowballAmong("an", -1, 104),
    new SnowballAmong("acan", 1488, 128),
    new SnowballAmong("iran", 1488, 100),
    new SnowballAmong("uran", 1488, 105),
    new SnowballAmong("tan", 1488, 113),
    new SnowballAmong("avan", 1488, 97),
    new SnowballAmong("evan", 1488, 96),
    new SnowballAmong("ivan", 1488, 98),
    new SnowballAmong("uvan", 1488, 99),
    new SnowballAmong("a\u010Dan", 1488, 102),
    new SnowballAmong("acen", -1, 124),
    new SnowballAmong("lucen", -1, 121),
    new SnowballAmong("a\u010Den", -1, 101),
    new SnowballAmong("lu\u010Den", -1, 117),
    new SnowballAmong("anin", -1, 10),
    new SnowballAmong("ao", -1, 104),
    new SnowballAmong("acao", 1503, 128),
    new SnowballAmong("astajao", 1503, 106),
    new SnowballAmong("istajao", 1503, 107),
    new SnowballAmong("ostajao", 1503, 108),
    new SnowballAmong("injao", 1503, 114),
    new SnowballAmong("irao", 1503, 100),
    new SnowballAmong("urao", 1503, 105),
    new SnowballAmong("tao", 1503, 113),
    new SnowballAmong("astao", 1511, 110),
    new SnowballAmong("istao", 1511, 111),
    new SnowballAmong("ostao", 1511, 112),
    new SnowballAmong("avao", 1503, 97),
    new SnowballAmong("evao", 1503, 96),
    new SnowballAmong("ivao", 1503, 98),
    new SnowballAmong("ovao", 1503, 76),
    new SnowballAmong("uvao", 1503, 99),
    new SnowballAmong("a\u010Dao", 1503, 102),
    new SnowballAmong("go", -1, 20),
    new SnowballAmong("ugo", 1521, 18),
    new SnowballAmong("io", -1, 116),
    new SnowballAmong("acio", 1523, 124),
    new SnowballAmong("lucio", 1523, 121),
    new SnowballAmong("lio", 1523, 24),
    new SnowballAmong("nio", 1523, 103),
    new SnowballAmong("rario", 1523, 21),
    new SnowballAmong("sio", 1523, 23),
    new SnowballAmong("rosio", 1529, 127),
    new SnowballAmong("jetio", 1523, 118),
    new SnowballAmong("otio", 1523, 22),
    new SnowballAmong("a\u010Dio", 1523, 101),
    new SnowballAmong("lu\u010Dio", 1523, 117),
    new SnowballAmong("ro\u0161io", 1523, 90),
    new SnowballAmong("bijo", -1, 32),
    new SnowballAmong("cijo", -1, 33),
    new SnowballAmong("dijo", -1, 34),
    new SnowballAmong("fijo", -1, 40),
    new SnowballAmong("gijo", -1, 39),
    new SnowballAmong("lijo", -1, 35),
    new SnowballAmong("mijo", -1, 37),
    new SnowballAmong("nijo", -1, 36),
    new SnowballAmong("pijo", -1, 41),
    new SnowballAmong("rijo", -1, 42),
    new SnowballAmong("sijo", -1, 43),
    new SnowballAmong("tijo", -1, 44),
    new SnowballAmong("zijo", -1, 45),
    new SnowballAmong("\u017Eijo", -1, 38),
    new SnowballAmong("anjo", -1, 84),
    new SnowballAmong("enjo", -1, 85),
    new SnowballAmong("snjo", -1, 122),
    new SnowballAmong("\u0161njo", -1, 86),
    new SnowballAmong("ko", -1, 95),
    new SnowballAmong("sko", 1554, 1),
    new SnowballAmong("\u0161ko", 1554, 2),
    new SnowballAmong("alo", -1, 104),
    new SnowballAmong("acalo", 1557, 128),
    new SnowballAmong("astajalo", 1557, 106),
    new SnowballAmong("istajalo", 1557, 107),
    new SnowballAmong("ostajalo", 1557, 108),
    new SnowballAmong("ijalo", 1557, 47),
    new SnowballAmong("injalo", 1557, 114),
    new SnowballAmong("nalo", 1557, 46),
    new SnowballAmong("iralo", 1557, 100),
    new SnowballAmong("uralo", 1557, 105),
    new SnowballAmong("talo", 1557, 113),
    new SnowballAmong("astalo", 1567, 110),
    new SnowballAmong("istalo", 1567, 111),
    new SnowballAmong("ostalo", 1567, 112),
    new SnowballAmong("avalo", 1557, 97),
    new SnowballAmong("evalo", 1557, 96),
    new SnowballAmong("ivalo", 1557, 98),
    new SnowballAmong("ovalo", 1557, 76),
    new SnowballAmong("uvalo", 1557, 99),
    new SnowballAmong("a\u010Dalo", 1557, 102),
    new SnowballAmong("elo", -1, 83),
    new SnowballAmong("ilo", -1, 116),
    new SnowballAmong("acilo", 1578, 124),
    new SnowballAmong("lucilo", 1578, 121),
    new SnowballAmong("nilo", 1578, 103),
    new SnowballAmong("rosilo", 1578, 127),
    new SnowballAmong("jetilo", 1578, 118),
    new SnowballAmong("a\u010Dilo", 1578, 101),
    new SnowballAmong("lu\u010Dilo", 1578, 117),
    new SnowballAmong("ro\u0161ilo", 1578, 90),
    new SnowballAmong("aslo", -1, 115),
    new SnowballAmong("nulo", -1, 13),
    new SnowballAmong("amo", -1, 104),
    new SnowballAmong("acamo", 1589, 128),
    new SnowballAmong("ramo", 1589, 52),
    new SnowballAmong("iramo", 1591, 100),
    new SnowballAmong("uramo", 1591, 105),
    new SnowballAmong("tamo", 1589, 113),
    new SnowballAmong("avamo", 1589, 97),
    new SnowballAmong("evamo", 1589, 96),
    new SnowballAmong("ivamo", 1589, 98),
    new SnowballAmong("uvamo", 1589, 99),
    new SnowballAmong("a\u010Damo", 1589, 102),
    new SnowballAmong("emo", -1, 119),
    new SnowballAmong("astademo", 1600, 110),
    new SnowballAmong("istademo", 1600, 111),
    new SnowballAmong("ostademo", 1600, 112),
    new SnowballAmong("astajemo", 1600, 106),
    new SnowballAmong("istajemo", 1600, 107),
    new SnowballAmong("ostajemo", 1600, 108),
    new SnowballAmong("ijemo", 1600, 116),
    new SnowballAmong("injemo", 1600, 114),
    new SnowballAmong("ujemo", 1600, 25),
    new SnowballAmong("lucujemo", 1609, 121),
    new SnowballAmong("irujemo", 1609, 100),
    new SnowballAmong("lu\u010Dujemo", 1609, 117),
    new SnowballAmong("lemo", 1600, 51),
    new SnowballAmong("nemo", 1600, 13),
    new SnowballAmong("astanemo", 1614, 110),
    new SnowballAmong("istanemo", 1614, 111),
    new SnowballAmong("ostanemo", 1614, 112),
    new SnowballAmong("etemo", 1600, 70),
    new SnowballAmong("astemo", 1600, 115),
    new SnowballAmong("imo", -1, 116),
    new SnowballAmong("acimo", 1620, 124),
    new SnowballAmong("lucimo", 1620, 121),
    new SnowballAmong("nimo", 1620, 13),
    new SnowballAmong("astanimo", 1623, 110),
    new SnowballAmong("istanimo", 1623, 111),
    new SnowballAmong("ostanimo", 1623, 112),
    new SnowballAmong("rosimo", 1620, 127),
    new SnowballAmong("etimo", 1620, 70),
    new SnowballAmong("jetimo", 1628, 118),
    new SnowballAmong("astimo", 1620, 115),
    new SnowballAmong("a\u010Dimo", 1620, 101),
    new SnowballAmong("lu\u010Dimo", 1620, 117),
    new SnowballAmong("ro\u0161imo", 1620, 90),
    new SnowballAmong("ajmo", -1, 104),
    new SnowballAmong("urajmo", 1634, 105),
    new SnowballAmong("tajmo", 1634, 113),
    new SnowballAmong("astajmo", 1636, 106),
    new SnowballAmong("istajmo", 1636, 107),
    new SnowballAmong("ostajmo", 1636, 108),
    new SnowballAmong("avajmo", 1634, 97),
    new SnowballAmong("evajmo", 1634, 96),
    new SnowballAmong("ivajmo", 1634, 98),
    new SnowballAmong("uvajmo", 1634, 99),
    new SnowballAmong("ijmo", -1, 116),
    new SnowballAmong("ujmo", -1, 25),
    new SnowballAmong("lucujmo", 1645, 121),
    new SnowballAmong("irujmo", 1645, 100),
    new SnowballAmong("lu\u010Dujmo", 1645, 117),
    new SnowballAmong("asmo", -1, 104),
    new SnowballAmong("acasmo", 1649, 128),
    new SnowballAmong("astajasmo", 1649, 106),
    new SnowballAmong("istajasmo", 1649, 107),
    new SnowballAmong("ostajasmo", 1649, 108),
    new SnowballAmong("injasmo", 1649, 114),
    new SnowballAmong("irasmo", 1649, 100),
    new SnowballAmong("urasmo", 1649, 105),
    new SnowballAmong("tasmo", 1649, 113),
    new SnowballAmong("avasmo", 1649, 97),
    new SnowballAmong("evasmo", 1649, 96),
    new SnowballAmong("ivasmo", 1649, 98),
    new SnowballAmong("ovasmo", 1649, 76),
    new SnowballAmong("uvasmo", 1649, 99),
    new SnowballAmong("a\u010Dasmo", 1649, 102),
    new SnowballAmong("ismo", -1, 116),
    new SnowballAmong("acismo", 1664, 124),
    new SnowballAmong("lucismo", 1664, 121),
    new SnowballAmong("nismo", 1664, 103),
    new SnowballAmong("rosismo", 1664, 127),
    new SnowballAmong("jetismo", 1664, 118),
    new SnowballAmong("a\u010Dismo", 1664, 101),
    new SnowballAmong("lu\u010Dismo", 1664, 117),
    new SnowballAmong("ro\u0161ismo", 1664, 90),
    new SnowballAmong("astadosmo", -1, 110),
    new SnowballAmong("istadosmo", -1, 111),
    new SnowballAmong("ostadosmo", -1, 112),
    new SnowballAmong("nusmo", -1, 13),
    new SnowballAmong("no", -1, 13),
    new SnowballAmong("ano", 1677, 104),
    new SnowballAmong("acano", 1678, 128),
    new SnowballAmong("urano", 1678, 105),
    new SnowballAmong("tano", 1678, 113),
    new SnowballAmong("avano", 1678, 97),
    new SnowballAmong("evano", 1678, 96),
    new SnowballAmong("ivano", 1678, 98),
    new SnowballAmong("uvano", 1678, 99),
    new SnowballAmong("a\u010Dano", 1678, 102),
    new SnowballAmong("aceno", 1677, 124),
    new SnowballAmong("luceno", 1677, 121),
    new SnowballAmong("a\u010Deno", 1677, 101),
    new SnowballAmong("lu\u010Deno", 1677, 117),
    new SnowballAmong("ino", 1677, 11),
    new SnowballAmong("cino", 1691, 137),
    new SnowballAmong("\u010Dino", 1691, 89),
    new SnowballAmong("ato", -1, 120),
    new SnowballAmong("ikato", 1694, 68),
    new SnowballAmong("lato", 1694, 69),
    new SnowballAmong("eto", -1, 70),
    new SnowballAmong("evito", -1, 92),
    new SnowballAmong("ovito", -1, 93),
    new SnowballAmong("asto", -1, 94),
    new SnowballAmong("esto", -1, 71),
    new SnowballAmong("isto", -1, 72),
    new SnowballAmong("ksto", -1, 73),
    new SnowballAmong("osto", -1, 74),
    new SnowballAmong("nuto", -1, 13),
    new SnowballAmong("nuo", -1, 13),
    new SnowballAmong("avo", -1, 77),
    new SnowballAmong("evo", -1, 78),
    new SnowballAmong("ivo", -1, 79),
    new SnowballAmong("ovo", -1, 80),
    new SnowballAmong("stvo", -1, 3),
    new SnowballAmong("\u0161tvo", -1, 4),
    new SnowballAmong("as", -1, 161),
    new SnowballAmong("acas", 1713, 128),
    new SnowballAmong("iras", 1713, 155),
    new SnowballAmong("uras", 1713, 156),
    new SnowballAmong("tas", 1713, 160),
    new SnowballAmong("avas", 1713, 144),
    new SnowballAmong("evas", 1713, 145),
    new SnowballAmong("ivas", 1713, 146),
    new SnowballAmong("uvas", 1713, 147),
    new SnowballAmong("es", -1, 163),
    new SnowballAmong("astades", 1722, 141),
    new SnowballAmong("istades", 1722, 142),
    new SnowballAmong("ostades", 1722, 143),
    new SnowballAmong("astajes", 1722, 138),
    new SnowballAmong("istajes", 1722, 139),
    new SnowballAmong("ostajes", 1722, 140),
    new SnowballAmong("ijes", 1722, 162),
    new SnowballAmong("injes", 1722, 150),
    new SnowballAmong("ujes", 1722, 157),
    new SnowballAmong("lucujes", 1731, 121),
    new SnowballAmong("irujes", 1731, 155),
    new SnowballAmong("nes", 1722, 164),
    new SnowballAmong("astanes", 1734, 141),
    new SnowballAmong("istanes", 1734, 142),
    new SnowballAmong("ostanes", 1734, 143),
    new SnowballAmong("etes", 1722, 153),
    new SnowballAmong("astes", 1722, 136),
    new SnowballAmong("is", -1, 162),
    new SnowballAmong("acis", 1740, 124),
    new SnowballAmong("lucis", 1740, 121),
    new SnowballAmong("nis", 1740, 158),
    new SnowballAmong("rosis", 1740, 127),
    new SnowballAmong("jetis", 1740, 149),
    new SnowballAmong("at", -1, 104),
    new SnowballAmong("acat", 1746, 128),
    new SnowballAmong("astajat", 1746, 106),
    new SnowballAmong("istajat", 1746, 107),
    new SnowballAmong("ostajat", 1746, 108),
    new SnowballAmong("injat", 1746, 114),
    new SnowballAmong("irat", 1746, 100),
    new SnowballAmong("urat", 1746, 105),
    new SnowballAmong("tat", 1746, 113),
    new SnowballAmong("astat", 1754, 110),
    new SnowballAmong("istat", 1754, 111),
    new SnowballAmong("ostat", 1754, 112),
    new SnowballAmong("avat", 1746, 97),
    new SnowballAmong("evat", 1746, 96),
    new SnowballAmong("ivat", 1746, 98),
    new SnowballAmong("irivat", 1760, 100),
    new SnowballAmong("ovat", 1746, 76),
    new SnowballAmong("uvat", 1746, 99),
    new SnowballAmong("a\u010Dat", 1746, 102),
    new SnowballAmong("it", -1, 116),
    new SnowballAmong("acit", 1765, 124),
    new SnowballAmong("lucit", 1765, 121),
    new SnowballAmong("rosit", 1765, 127),
    new SnowballAmong("jetit", 1765, 118),
    new SnowballAmong("a\u010Dit", 1765, 101),
    new SnowballAmong("lu\u010Dit", 1765, 117),
    new SnowballAmong("ro\u0161it", 1765, 90),
    new SnowballAmong("nut", -1, 13),
    new SnowballAmong("astadu", -1, 110),
    new SnowballAmong("istadu", -1, 111),
    new SnowballAmong("ostadu", -1, 112),
    new SnowballAmong("gu", -1, 20),
    new SnowballAmong("logu", 1777, 19),
    new SnowballAmong("ugu", 1777, 18),
    new SnowballAmong("ahu", -1, 104),
    new SnowballAmong("acahu", 1780, 128),
    new SnowballAmong("astajahu", 1780, 106),
    new SnowballAmong("istajahu", 1780, 107),
    new SnowballAmong("ostajahu", 1780, 108),
    new SnowballAmong("injahu", 1780, 114),
    new SnowballAmong("irahu", 1780, 100),
    new SnowballAmong("urahu", 1780, 105),
    new SnowballAmong("avahu", 1780, 97),
    new SnowballAmong("evahu", 1780, 96),
    new SnowballAmong("ivahu", 1780, 98),
    new SnowballAmong("ovahu", 1780, 76),
    new SnowballAmong("uvahu", 1780, 99),
    new SnowballAmong("a\u010Dahu", 1780, 102),
    new SnowballAmong("aju", -1, 104),
    new SnowballAmong("caju", 1794, 26),
    new SnowballAmong("acaju", 1795, 128),
    new SnowballAmong("laju", 1794, 30),
    new SnowballAmong("raju", 1794, 31),
    new SnowballAmong("iraju", 1798, 100),
    new SnowballAmong("uraju", 1798, 105),
    new SnowballAmong("taju", 1794, 113),
    new SnowballAmong("astaju", 1801, 106),
    new SnowballAmong("istaju", 1801, 107),
    new SnowballAmong("ostaju", 1801, 108),
    new SnowballAmong("avaju", 1794, 97),
    new SnowballAmong("evaju", 1794, 96),
    new SnowballAmong("ivaju", 1794, 98),
    new SnowballAmong("uvaju", 1794, 99),
    new SnowballAmong("\u0107aju", 1794, 28),
    new SnowballAmong("\u010Daju", 1794, 27),
    new SnowballAmong("a\u010Daju", 1810, 102),
    new SnowballAmong("\u0111aju", 1794, 29),
    new SnowballAmong("iju", -1, 116),
    new SnowballAmong("biju", 1813, 32),
    new SnowballAmong("ciju", 1813, 33),
    new SnowballAmong("diju", 1813, 34),
    new SnowballAmong("fiju", 1813, 40),
    new SnowballAmong("giju", 1813, 39),
    new SnowballAmong("anjiju", 1813, 84),
    new SnowballAmong("enjiju", 1813, 85),
    new SnowballAmong("snjiju", 1813, 122),
    new SnowballAmong("\u0161njiju", 1813, 86),
    new SnowballAmong("kiju", 1813, 95),
    new SnowballAmong("liju", 1813, 24),
    new SnowballAmong("eliju", 1824, 83),
    new SnowballAmong("miju", 1813, 37),
    new SnowballAmong("niju", 1813, 13),
    new SnowballAmong("ganiju", 1827, 9),
    new SnowballAmong("maniju", 1827, 6),
    new SnowballAmong("paniju", 1827, 7),
    new SnowballAmong("raniju", 1827, 8),
    new SnowballAmong("taniju", 1827, 5),
    new SnowballAmong("piju", 1813, 41),
    new SnowballAmong("riju", 1813, 42),
    new SnowballAmong("rariju", 1834, 21),
    new SnowballAmong("siju", 1813, 23),
    new SnowballAmong("osiju", 1836, 123),
    new SnowballAmong("tiju", 1813, 44),
    new SnowballAmong("atiju", 1838, 120),
    new SnowballAmong("otiju", 1838, 22),
    new SnowballAmong("aviju", 1813, 77),
    new SnowballAmong("eviju", 1813, 78),
    new SnowballAmong("iviju", 1813, 79),
    new SnowballAmong("oviju", 1813, 80),
    new SnowballAmong("ziju", 1813, 45),
    new SnowballAmong("o\u0161iju", 1813, 91),
    new SnowballAmong("\u017Eiju", 1813, 38),
    new SnowballAmong("anju", -1, 84),
    new SnowballAmong("enju", -1, 85),
    new SnowballAmong("snju", -1, 122),
    new SnowballAmong("\u0161nju", -1, 86),
    new SnowballAmong("uju", -1, 25),
    new SnowballAmong("lucuju", 1852, 121),
    new SnowballAmong("iruju", 1852, 100),
    new SnowballAmong("lu\u010Duju", 1852, 117),
    new SnowballAmong("ku", -1, 95),
    new SnowballAmong("sku", 1856, 1),
    new SnowballAmong("\u0161ku", 1856, 2),
    new SnowballAmong("alu", -1, 104),
    new SnowballAmong("ijalu", 1859, 47),
    new SnowballAmong("nalu", 1859, 46),
    new SnowballAmong("elu", -1, 83),
    new SnowballAmong("ilu", -1, 116),
    new SnowballAmong("ozilu", 1863, 48),
    new SnowballAmong("olu", -1, 50),
    new SnowballAmong("ramu", -1, 52),
    new SnowballAmong("acemu", -1, 124),
    new SnowballAmong("ecemu", -1, 125),
    new SnowballAmong("ucemu", -1, 126),
    new SnowballAmong("anjijemu", -1, 84),
    new SnowballAmong("enjijemu", -1, 85),
    new SnowballAmong("snjijemu", -1, 122),
    new SnowballAmong("\u0161njijemu", -1, 86),
    new SnowballAmong("kijemu", -1, 95),
    new SnowballAmong("skijemu", 1874, 1),
    new SnowballAmong("\u0161kijemu", 1874, 2),
    new SnowballAmong("elijemu", -1, 83),
    new SnowballAmong("nijemu", -1, 13),
    new SnowballAmong("osijemu", -1, 123),
    new SnowballAmong("atijemu", -1, 120),
    new SnowballAmong("evitijemu", -1, 92),
    new SnowballAmong("ovitijemu", -1, 93),
    new SnowballAmong("astijemu", -1, 94),
    new SnowballAmong("avijemu", -1, 77),
    new SnowballAmong("evijemu", -1, 78),
    new SnowballAmong("ivijemu", -1, 79),
    new SnowballAmong("ovijemu", -1, 80),
    new SnowballAmong("o\u0161ijemu", -1, 91),
    new SnowballAmong("anjemu", -1, 84),
    new SnowballAmong("enjemu", -1, 85),
    new SnowballAmong("snjemu", -1, 122),
    new SnowballAmong("\u0161njemu", -1, 86),
    new SnowballAmong("kemu", -1, 95),
    new SnowballAmong("skemu", 1893, 1),
    new SnowballAmong("\u0161kemu", 1893, 2),
    new SnowballAmong("lemu", -1, 51),
    new SnowballAmong("elemu", 1896, 83),
    new SnowballAmong("nemu", -1, 13),
    new SnowballAmong("anemu", 1898, 10),
    new SnowballAmong("enemu", 1898, 87),
    new SnowballAmong("snemu", 1898, 159),
    new SnowballAmong("\u0161nemu", 1898, 88),
    new SnowballAmong("osemu", -1, 123),
    new SnowballAmong("atemu", -1, 120),
    new SnowballAmong("evitemu", -1, 92),
    new SnowballAmong("ovitemu", -1, 93),
    new SnowballAmong("astemu", -1, 94),
    new SnowballAmong("avemu", -1, 77),
    new SnowballAmong("evemu", -1, 78),
    new SnowballAmong("ivemu", -1, 79),
    new SnowballAmong("ovemu", -1, 80),
    new SnowballAmong("a\u0107emu", -1, 14),
    new SnowballAmong("e\u0107emu", -1, 15),
    new SnowballAmong("u\u0107emu", -1, 16),
    new SnowballAmong("o\u0161emu", -1, 91),
    new SnowballAmong("acomu", -1, 124),
    new SnowballAmong("ecomu", -1, 125),
    new SnowballAmong("ucomu", -1, 126),
    new SnowballAmong("anjomu", -1, 84),
    new SnowballAmong("enjomu", -1, 85),
    new SnowballAmong("snjomu", -1, 122),
    new SnowballAmong("\u0161njomu", -1, 86),
    new SnowballAmong("komu", -1, 95),
    new SnowballAmong("skomu", 1923, 1),
    new SnowballAmong("\u0161komu", 1923, 2),
    new SnowballAmong("elomu", -1, 83),
    new SnowballAmong("nomu", -1, 13),
    new SnowballAmong("cinomu", 1927, 137),
    new SnowballAmong("\u010Dinomu", 1927, 89),
    new SnowballAmong("osomu", -1, 123),
    new SnowballAmong("atomu", -1, 120),
    new SnowballAmong("evitomu", -1, 92),
    new SnowballAmong("ovitomu", -1, 93),
    new SnowballAmong("astomu", -1, 94),
    new SnowballAmong("avomu", -1, 77),
    new SnowballAmong("evomu", -1, 78),
    new SnowballAmong("ivomu", -1, 79),
    new SnowballAmong("ovomu", -1, 80),
    new SnowballAmong("a\u0107omu", -1, 14),
    new SnowballAmong("e\u0107omu", -1, 15),
    new SnowballAmong("u\u0107omu", -1, 16),
    new SnowballAmong("o\u0161omu", -1, 91),
    new SnowballAmong("nu", -1, 13),
    new SnowballAmong("anu", 1943, 10),
    new SnowballAmong("astanu", 1944, 110),
    new SnowballAmong("istanu", 1944, 111),
    new SnowballAmong("ostanu", 1944, 112),
    new SnowballAmong("inu", 1943, 11),
    new SnowballAmong("cinu", 1948, 137),
    new SnowballAmong("aninu", 1948, 10),
    new SnowballAmong("\u010Dinu", 1948, 89),
    new SnowballAmong("onu", 1943, 12),
    new SnowballAmong("aru", -1, 53),
    new SnowballAmong("dru", -1, 54),
    new SnowballAmong("eru", -1, 55),
    new SnowballAmong("oru", -1, 56),
    new SnowballAmong("basu", -1, 135),
    new SnowballAmong("gasu", -1, 131),
    new SnowballAmong("jasu", -1, 129),
    new SnowballAmong("kasu", -1, 133),
    new SnowballAmong("nasu", -1, 132),
    new SnowballAmong("tasu", -1, 130),
    new SnowballAmong("vasu", -1, 134),
    new SnowballAmong("esu", -1, 57),
    new SnowballAmong("isu", -1, 58),
    new SnowballAmong("osu", -1, 123),
    new SnowballAmong("atu", -1, 120),
    new SnowballAmong("ikatu", 1967, 68),
    new SnowballAmong("latu", 1967, 69),
    new SnowballAmong("etu", -1, 70),
    new SnowballAmong("evitu", -1, 92),
    new SnowballAmong("ovitu", -1, 93),
    new SnowballAmong("astu", -1, 94),
    new SnowballAmong("estu", -1, 71),
    new SnowballAmong("istu", -1, 72),
    new SnowballAmong("kstu", -1, 73),
    new SnowballAmong("ostu", -1, 74),
    new SnowballAmong("i\u0161tu", -1, 75),
    new SnowballAmong("avu", -1, 77),
    new SnowballAmong("evu", -1, 78),
    new SnowballAmong("ivu", -1, 79),
    new SnowballAmong("ovu", -1, 80),
    new SnowballAmong("lovu", 1982, 82),
    new SnowballAmong("movu", 1982, 81),
    new SnowballAmong("stvu", -1, 3),
    new SnowballAmong("\u0161tvu", -1, 4),
    new SnowballAmong("ba\u0161u", -1, 63),
    new SnowballAmong("ga\u0161u", -1, 64),
    new SnowballAmong("ja\u0161u", -1, 61),
    new SnowballAmong("ka\u0161u", -1, 62),
    new SnowballAmong("na\u0161u", -1, 60),
    new SnowballAmong("ta\u0161u", -1, 59),
    new SnowballAmong("va\u0161u", -1, 65),
    new SnowballAmong("e\u0161u", -1, 66),
    new SnowballAmong("i\u0161u", -1, 67),
    new SnowballAmong("o\u0161u", -1, 91),
    new SnowballAmong("avav", -1, 97),
    new SnowballAmong("evav", -1, 96),
    new SnowballAmong("ivav", -1, 98),
    new SnowballAmong("uvav", -1, 99),
    new SnowballAmong("kov", -1, 95),
    new SnowballAmong("a\u0161", -1, 104),
    new SnowballAmong("ira\u0161", 2002, 100),
    new SnowballAmong("ura\u0161", 2002, 105),
    new SnowballAmong("ta\u0161", 2002, 113),
    new SnowballAmong("ava\u0161", 2002, 97),
    new SnowballAmong("eva\u0161", 2002, 96),
    new SnowballAmong("iva\u0161", 2002, 98),
    new SnowballAmong("uva\u0161", 2002, 99),
    new SnowballAmong("a\u010Da\u0161", 2002, 102),
    new SnowballAmong("e\u0161", -1, 119),
    new SnowballAmong("astade\u0161", 2011, 110),
    new SnowballAmong("istade\u0161", 2011, 111),
    new SnowballAmong("ostade\u0161", 2011, 112),
    new SnowballAmong("astaje\u0161", 2011, 106),
    new SnowballAmong("istaje\u0161", 2011, 107),
    new SnowballAmong("ostaje\u0161", 2011, 108),
    new SnowballAmong("ije\u0161", 2011, 116),
    new SnowballAmong("inje\u0161", 2011, 114),
    new SnowballAmong("uje\u0161", 2011, 25),
    new SnowballAmong("iruje\u0161", 2020, 100),
    new SnowballAmong("lu\u010Duje\u0161", 2020, 117),
    new SnowballAmong("ne\u0161", 2011, 13),
    new SnowballAmong("astane\u0161", 2023, 110),
    new SnowballAmong("istane\u0161", 2023, 111),
    new SnowballAmong("ostane\u0161", 2023, 112),
    new SnowballAmong("ete\u0161", 2011, 70),
    new SnowballAmong("aste\u0161", 2011, 115),
    new SnowballAmong("i\u0161", -1, 116),
    new SnowballAmong("ni\u0161", 2029, 103),
    new SnowballAmong("jeti\u0161", 2029, 118),
    new SnowballAmong("a\u010Di\u0161", 2029, 101),
    new SnowballAmong("lu\u010Di\u0161", 2029, 117),
    new SnowballAmong("ro\u0161i\u0161", 2029, 90)
  };

  private static final SnowballAmong[] a_3 = {
    new SnowballAmong("a", -1, 1),
    new SnowballAmong("oga", 0, 1),
    new SnowballAmong("ama", 0, 1),
    new SnowballAmong("ima", 0, 1),
    new SnowballAmong("ena", 0, 1),
    new SnowballAmong("e", -1, 1),
    new SnowballAmong("og", -1, 1),
    new SnowballAmong("anog", 6, 1),
    new SnowballAmong("enog", 6, 1),
    new SnowballAmong("anih", -1, 1),
    new SnowballAmong("enih", -1, 1),
    new SnowballAmong("i", -1, 1),
    new SnowballAmong("ani", 11, 1),
    new SnowballAmong("eni", 11, 1),
    new SnowballAmong("anoj", -1, 1),
    new SnowballAmong("enoj", -1, 1),
    new SnowballAmong("anim", -1, 1),
    new SnowballAmong("enim", -1, 1),
    new SnowballAmong("om", -1, 1),
    new SnowballAmong("enom", 18, 1),
    new SnowballAmong("o", -1, 1),
    new SnowballAmong("ano", 20, 1),
    new SnowballAmong("eno", 20, 1),
    new SnowballAmong("ost", -1, 1),
    new SnowballAmong("u", -1, 1),
    new SnowballAmong("enu", 24, 1)
  };

  private static final char[] g_v = {17, 65, 16};

  private static final char[] g_sa = {65, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 128};

  private static final char[] g_ca = {
    119, 95, 23, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 32, 136, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 128, 0, 0, 0, 16
  };

  private static final char[] g_rg = {1};

  private int I_p1;
  private boolean B_no_diacritics;

  private boolean r_cyr_to_lat() {
    int among_var;
    int v_1 = cursor;
    lab0:
    {
      while (true) {
        int v_2 = cursor;
        lab1:
        {
          golab2:
          while (true) {
            int v_3 = cursor;
            lab3:
            {
              bra = cursor;
              among_var = find_among(a_0);
              if (among_var == 0) {
                break lab3;
              }
              ket = cursor;
              switch (among_var) {
                case 1:
                  slice_from("a");
                  break;
                case 2:
                  slice_from("b");
                  break;
                case 3:
                  slice_from("v");
                  break;
                case 4:
                  slice_from("g");
                  break;
                case 5:
                  slice_from("d");
                  break;
                case 6:
                  slice_from("\u0111");
                  break;
                case 7:
                  slice_from("e");
                  break;
                case 8:
                  slice_from("\u017E");
                  break;
                case 9:
                  slice_from("z");
                  break;
                case 10:
                  slice_from("i");
                  break;
                case 11:
                  slice_from("j");
                  break;
                case 12:
                  slice_from("k");
                  break;
                case 13:
                  slice_from("l");
                  break;
                case 14:
                  slice_from("lj");
                  break;
                case 15:
                  slice_from("m");
                  break;
                case 16:
                  slice_from("n");
                  break;
                case 17:
                  slice_from("nj");
                  break;
                case 18:
                  slice_from("o");
                  break;
                case 19:
                  slice_from("p");
                  break;
                case 20:
                  slice_from("r");
                  break;
                case 21:
                  slice_from("s");
                  break;
                case 22:
                  slice_from("t");
                  break;
                case 23:
                  slice_from("\u0107");
                  break;
                case 24:
                  slice_from("u");
                  break;
                case 25:
                  slice_from("f");
                  break;
                case 26:
                  slice_from("h");
                  break;
                case 27:
                  slice_from("c");
                  break;
                case 28:
                  slice_from("\u010D");
                  break;
                case 29:
                  slice_from("d\u017E");
                  break;
                case 30:
                  slice_from("\u0161");
                  break;
              }
              cursor = v_3;
              break golab2;
            }
            cursor = v_3;
            if (cursor >= limit) {
              break lab1;
            }
            cursor++;
          }
          continue;
        }
        cursor = v_2;
        break;
      }
    }
    cursor = v_1;
    return true;
  }

  private boolean r_prelude() {
    int v_1 = cursor;
    lab0:
    {
      while (true) {
        int v_2 = cursor;
        lab1:
        {
          golab2:
          while (true) {
            int v_3 = cursor;
            lab3:
            {
              if (!(in_grouping(g_ca, 98, 382))) {
                break lab3;
              }
              bra = cursor;
              if (!(eq_s("ije"))) {
                break lab3;
              }
              ket = cursor;
              if (!(in_grouping(g_ca, 98, 382))) {
                break lab3;
              }
              slice_from("e");
              cursor = v_3;
              break golab2;
            }
            cursor = v_3;
            if (cursor >= limit) {
              break lab1;
            }
            cursor++;
          }
          continue;
        }
        cursor = v_2;
        break;
      }
    }
    cursor = v_1;
    int v_4 = cursor;
    lab4:
    {
      while (true) {
        int v_5 = cursor;
        lab5:
        {
          golab6:
          while (true) {
            int v_6 = cursor;
            lab7:
            {
              if (!(in_grouping(g_ca, 98, 382))) {
                break lab7;
              }
              bra = cursor;
              if (!(eq_s("je"))) {
                break lab7;
              }
              ket = cursor;
              if (!(in_grouping(g_ca, 98, 382))) {
                break lab7;
              }
              slice_from("e");
              cursor = v_6;
              break golab6;
            }
            cursor = v_6;
            if (cursor >= limit) {
              break lab5;
            }
            cursor++;
          }
          continue;
        }
        cursor = v_5;
        break;
      }
    }
    cursor = v_4;
    int v_7 = cursor;
    lab8:
    {
      while (true) {
        int v_8 = cursor;
        lab9:
        {
          golab10:
          while (true) {
            int v_9 = cursor;
            lab11:
            {
              bra = cursor;
              if (!(eq_s("dj"))) {
                break lab11;
              }
              ket = cursor;
              slice_from("\u0111");
              cursor = v_9;
              break golab10;
            }
            cursor = v_9;
            if (cursor >= limit) {
              break lab9;
            }
            cursor++;
          }
          continue;
        }
        cursor = v_8;
        break;
      }
    }
    cursor = v_7;
    return true;
  }

  private boolean r_mark_regions() {
    B_no_diacritics = true;
    int v_1 = cursor;
    lab0:
    {
      if (!go_out_grouping(g_sa, 263, 382)) {
        break lab0;
      }
      cursor++;
      B_no_diacritics = false;
    }
    cursor = v_1;
    I_p1 = limit;
    int v_2 = cursor;
    lab1:
    {
      if (!go_out_grouping(g_v, 97, 117)) {
        break lab1;
      }
      cursor++;
      I_p1 = cursor;
      if (I_p1 >= 2) {
        break lab1;
      }
      if (!go_in_grouping(g_v, 97, 117)) {
        break lab1;
      }
      cursor++;
      I_p1 = cursor;
    }
    cursor = v_2;
    int v_3 = cursor;
    lab2:
    {
      golab3:
      while (true) {
        lab4:
        {
          if (!(eq_s("r"))) {
            break lab4;
          }
          break golab3;
        }
        if (cursor >= limit) {
          break lab2;
        }
        cursor++;
      }
      lab5:
      {
        int v_4 = cursor;
        lab6:
        {
          if (cursor < 2) {
            break lab6;
          }
          break lab5;
        }
        cursor = v_4;
        if (!go_in_grouping(g_rg, 114, 114)) {
          break lab2;
        }
        cursor++;
      }
      if ((I_p1 - cursor) <= 1) {
        break lab2;
      }
      I_p1 = cursor;
    }
    cursor = v_3;
    return true;
  }

  private boolean r_R1() {
    return I_p1 <= cursor;
  }

  private boolean r_Step_1() {
    int among_var;
    ket = cursor;
    among_var = find_among_b(a_1);
    if (among_var == 0) {
      return false;
    }
    bra = cursor;
    switch (among_var) {
      case 1:
        slice_from("loga");
        break;
      case 2:
        slice_from("peh");
        break;
      case 3:
        slice_from("vojka");
        break;
      case 4:
        slice_from("bojka");
        break;
      case 5:
        slice_from("jak");
        break;
      case 6:
        slice_from("\u010Dajni");
        break;
      case 7:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("cajni");
        break;
      case 8:
        slice_from("erni");
        break;
      case 9:
        slice_from("larni");
        break;
      case 10:
        slice_from("esni");
        break;
      case 11:
        slice_from("anjca");
        break;
      case 12:
        slice_from("ajca");
        break;
      case 13:
        slice_from("ljca");
        break;
      case 14:
        slice_from("ejca");
        break;
      case 15:
        slice_from("ojca");
        break;
      case 16:
        slice_from("ajka");
        break;
      case 17:
        slice_from("ojka");
        break;
      case 18:
        slice_from("\u0161ca");
        break;
      case 19:
        slice_from("ing");
        break;
      case 20:
        slice_from("tvenik");
        break;
      case 21:
        slice_from("tetika");
        break;
      case 22:
        slice_from("nstva");
        break;
      case 23:
        slice_from("nik");
        break;
      case 24:
        slice_from("tik");
        break;
      case 25:
        slice_from("zik");
        break;
      case 26:
        slice_from("snik");
        break;
      case 27:
        slice_from("kusi");
        break;
      case 28:
        slice_from("kusni");
        break;
      case 29:
        slice_from("kustva");
        break;
      case 30:
        slice_from("du\u0161ni");
        break;
      case 31:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("dusni");
        break;
      case 32:
        slice_from("antni");
        break;
      case 33:
        slice_from("bilni");
        break;
      case 34:
        slice_from("tilni");
        break;
      case 35:
        slice_from("avilni");
        break;
      case 36:
        slice_from("silni");
        break;
      case 37:
        slice_from("gilni");
        break;
      case 38:
        slice_from("rilni");
        break;
      case 39:
        slice_from("nilni");
        break;
      case 40:
        slice_from("alni");
        break;
      case 41:
        slice_from("ozni");
        break;
      case 42:
        slice_from("ravi");
        break;
      case 43:
        slice_from("stavni");
        break;
      case 44:
        slice_from("pravni");
        break;
      case 45:
        slice_from("tivni");
        break;
      case 46:
        slice_from("sivni");
        break;
      case 47:
        slice_from("atni");
        break;
      case 48:
        slice_from("enta");
        break;
      case 49:
        slice_from("tetni");
        break;
      case 50:
        slice_from("pletni");
        break;
      case 51:
        slice_from("\u0161avi");
        break;
      case 52:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("savi");
        break;
      case 53:
        slice_from("anta");
        break;
      case 54:
        slice_from("a\u010Dka");
        break;
      case 55:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("acka");
        break;
      case 56:
        slice_from("u\u0161ka");
        break;
      case 57:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("uska");
        break;
      case 58:
        slice_from("atka");
        break;
      case 59:
        slice_from("etka");
        break;
      case 60:
        slice_from("itka");
        break;
      case 61:
        slice_from("otka");
        break;
      case 62:
        slice_from("utka");
        break;
      case 63:
        slice_from("eskna");
        break;
      case 64:
        slice_from("ti\u010Dni");
        break;
      case 65:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("ticni");
        break;
      case 66:
        slice_from("ojska");
        break;
      case 67:
        slice_from("esma");
        break;
      case 68:
        slice_from("metra");
        break;
      case 69:
        slice_from("centra");
        break;
      case 70:
        slice_from("istra");
        break;
      case 71:
        slice_from("osti");
        break;
      case 72:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("osti");
        break;
      case 73:
        slice_from("dba");
        break;
      case 74:
        slice_from("\u010Dka");
        break;
      case 75:
        slice_from("mca");
        break;
      case 76:
        slice_from("nca");
        break;
      case 77:
        slice_from("voljni");
        break;
      case 78:
        slice_from("anki");
        break;
      case 79:
        slice_from("vca");
        break;
      case 80:
        slice_from("sca");
        break;
      case 81:
        slice_from("rca");
        break;
      case 82:
        slice_from("alca");
        break;
      case 83:
        slice_from("elca");
        break;
      case 84:
        slice_from("olca");
        break;
      case 85:
        slice_from("njca");
        break;
      case 86:
        slice_from("ekta");
        break;
      case 87:
        slice_from("izma");
        break;
      case 88:
        slice_from("jebi");
        break;
      case 89:
        slice_from("baci");
        break;
      case 90:
        slice_from("a\u0161ni");
        break;
      case 91:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("asni");
        break;
    }
    return true;
  }

  private boolean r_Step_2() {
    int among_var;
    ket = cursor;
    among_var = find_among_b(a_2);
    if (among_var == 0) {
      return false;
    }
    bra = cursor;
    if (!r_R1()) {
      return false;
    }
    switch (among_var) {
      case 1:
        slice_from("sk");
        break;
      case 2:
        slice_from("\u0161k");
        break;
      case 3:
        slice_from("stv");
        break;
      case 4:
        slice_from("\u0161tv");
        break;
      case 5:
        slice_from("tanij");
        break;
      case 6:
        slice_from("manij");
        break;
      case 7:
        slice_from("panij");
        break;
      case 8:
        slice_from("ranij");
        break;
      case 9:
        slice_from("ganij");
        break;
      case 10:
        slice_from("an");
        break;
      case 11:
        slice_from("in");
        break;
      case 12:
        slice_from("on");
        break;
      case 13:
        slice_from("n");
        break;
      case 14:
        slice_from("a\u0107");
        break;
      case 15:
        slice_from("e\u0107");
        break;
      case 16:
        slice_from("u\u0107");
        break;
      case 17:
        slice_from("ugov");
        break;
      case 18:
        slice_from("ug");
        break;
      case 19:
        slice_from("log");
        break;
      case 20:
        slice_from("g");
        break;
      case 21:
        slice_from("rari");
        break;
      case 22:
        slice_from("oti");
        break;
      case 23:
        slice_from("si");
        break;
      case 24:
        slice_from("li");
        break;
      case 25:
        slice_from("uj");
        break;
      case 26:
        slice_from("caj");
        break;
      case 27:
        slice_from("\u010Daj");
        break;
      case 28:
        slice_from("\u0107aj");
        break;
      case 29:
        slice_from("\u0111aj");
        break;
      case 30:
        slice_from("laj");
        break;
      case 31:
        slice_from("raj");
        break;
      case 32:
        slice_from("bij");
        break;
      case 33:
        slice_from("cij");
        break;
      case 34:
        slice_from("dij");
        break;
      case 35:
        slice_from("lij");
        break;
      case 36:
        slice_from("nij");
        break;
      case 37:
        slice_from("mij");
        break;
      case 38:
        slice_from("\u017Eij");
        break;
      case 39:
        slice_from("gij");
        break;
      case 40:
        slice_from("fij");
        break;
      case 41:
        slice_from("pij");
        break;
      case 42:
        slice_from("rij");
        break;
      case 43:
        slice_from("sij");
        break;
      case 44:
        slice_from("tij");
        break;
      case 45:
        slice_from("zij");
        break;
      case 46:
        slice_from("nal");
        break;
      case 47:
        slice_from("ijal");
        break;
      case 48:
        slice_from("ozil");
        break;
      case 49:
        slice_from("olov");
        break;
      case 50:
        slice_from("ol");
        break;
      case 51:
        slice_from("lem");
        break;
      case 52:
        slice_from("ram");
        break;
      case 53:
        slice_from("ar");
        break;
      case 54:
        slice_from("dr");
        break;
      case 55:
        slice_from("er");
        break;
      case 56:
        slice_from("or");
        break;
      case 57:
        slice_from("es");
        break;
      case 58:
        slice_from("is");
        break;
      case 59:
        slice_from("ta\u0161");
        break;
      case 60:
        slice_from("na\u0161");
        break;
      case 61:
        slice_from("ja\u0161");
        break;
      case 62:
        slice_from("ka\u0161");
        break;
      case 63:
        slice_from("ba\u0161");
        break;
      case 64:
        slice_from("ga\u0161");
        break;
      case 65:
        slice_from("va\u0161");
        break;
      case 66:
        slice_from("e\u0161");
        break;
      case 67:
        slice_from("i\u0161");
        break;
      case 68:
        slice_from("ikat");
        break;
      case 69:
        slice_from("lat");
        break;
      case 70:
        slice_from("et");
        break;
      case 71:
        slice_from("est");
        break;
      case 72:
        slice_from("ist");
        break;
      case 73:
        slice_from("kst");
        break;
      case 74:
        slice_from("ost");
        break;
      case 75:
        slice_from("i\u0161t");
        break;
      case 76:
        slice_from("ova");
        break;
      case 77:
        slice_from("av");
        break;
      case 78:
        slice_from("ev");
        break;
      case 79:
        slice_from("iv");
        break;
      case 80:
        slice_from("ov");
        break;
      case 81:
        slice_from("mov");
        break;
      case 82:
        slice_from("lov");
        break;
      case 83:
        slice_from("el");
        break;
      case 84:
        slice_from("anj");
        break;
      case 85:
        slice_from("enj");
        break;
      case 86:
        slice_from("\u0161nj");
        break;
      case 87:
        slice_from("en");
        break;
      case 88:
        slice_from("\u0161n");
        break;
      case 89:
        slice_from("\u010Din");
        break;
      case 90:
        slice_from("ro\u0161i");
        break;
      case 91:
        slice_from("o\u0161");
        break;
      case 92:
        slice_from("evit");
        break;
      case 93:
        slice_from("ovit");
        break;
      case 94:
        slice_from("ast");
        break;
      case 95:
        slice_from("k");
        break;
      case 96:
        slice_from("eva");
        break;
      case 97:
        slice_from("ava");
        break;
      case 98:
        slice_from("iva");
        break;
      case 99:
        slice_from("uva");
        break;
      case 100:
        slice_from("ir");
        break;
      case 101:
        slice_from("a\u010D");
        break;
      case 102:
        slice_from("a\u010Da");
        break;
      case 103:
        slice_from("ni");
        break;
      case 104:
        slice_from("a");
        break;
      case 105:
        slice_from("ur");
        break;
      case 106:
        slice_from("astaj");
        break;
      case 107:
        slice_from("istaj");
        break;
      case 108:
        slice_from("ostaj");
        break;
      case 109:
        slice_from("aj");
        break;
      case 110:
        slice_from("asta");
        break;
      case 111:
        slice_from("ista");
        break;
      case 112:
        slice_from("osta");
        break;
      case 113:
        slice_from("ta");
        break;
      case 114:
        slice_from("inj");
        break;
      case 115:
        slice_from("as");
        break;
      case 116:
        slice_from("i");
        break;
      case 117:
        slice_from("lu\u010D");
        break;
      case 118:
        slice_from("jeti");
        break;
      case 119:
        slice_from("e");
        break;
      case 120:
        slice_from("at");
        break;
      case 121:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("luc");
        break;
      case 122:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("snj");
        break;
      case 123:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("os");
        break;
      case 124:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("ac");
        break;
      case 125:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("ec");
        break;
      case 126:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("uc");
        break;
      case 127:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("rosi");
        break;
      case 128:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("aca");
        break;
      case 129:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("jas");
        break;
      case 130:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("tas");
        break;
      case 131:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("gas");
        break;
      case 132:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("nas");
        break;
      case 133:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("kas");
        break;
      case 134:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("vas");
        break;
      case 135:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("bas");
        break;
      case 136:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("as");
        break;
      case 137:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("cin");
        break;
      case 138:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("astaj");
        break;
      case 139:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("istaj");
        break;
      case 140:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("ostaj");
        break;
      case 141:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("asta");
        break;
      case 142:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("ista");
        break;
      case 143:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("osta");
        break;
      case 144:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("ava");
        break;
      case 145:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("eva");
        break;
      case 146:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("iva");
        break;
      case 147:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("uva");
        break;
      case 148:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("ova");
        break;
      case 149:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("jeti");
        break;
      case 150:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("inj");
        break;
      case 151:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("ist");
        break;
      case 152:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("es");
        break;
      case 153:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("et");
        break;
      case 154:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("is");
        break;
      case 155:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("ir");
        break;
      case 156:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("ur");
        break;
      case 157:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("uj");
        break;
      case 158:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("ni");
        break;
      case 159:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("sn");
        break;
      case 160:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("ta");
        break;
      case 161:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("a");
        break;
      case 162:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("i");
        break;
      case 163:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("e");
        break;
      case 164:
        if (!B_no_diacritics) {
          return false;
        }
        slice_from("n");
        break;
    }
    return true;
  }

  private boolean r_Step_3() {
    ket = cursor;
    if (find_among_b(a_3) == 0) {
      return false;
    }
    bra = cursor;
    if (!r_R1()) {
      return false;
    }
    slice_from("");
    return true;
  }

  @Override
  public boolean stem() {
    r_cyr_to_lat();
    r_prelude();
    r_mark_regions();
    limit_backward = cursor;
    cursor = limit;
    int v_1 = limit - cursor;
    r_Step_1();
    cursor = limit - v_1;
    int v_2 = limit - cursor;
    lab0:
    {
      lab1:
      {
        int v_3 = limit - cursor;
        lab2:
        {
          if (!r_Step_2()) {
            break lab2;
          }
          break lab1;
        }
        cursor = limit - v_3;
        if (!r_Step_3()) {
          break lab0;
        }
      }
    }
    cursor = limit - v_2;
    cursor = limit_backward;
    return true;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof SerbianSnowballCore;
  }

  @Override
  public int hashCode() {
    return SerbianSnowballCore.class.getName().hashCode();
  }
}
