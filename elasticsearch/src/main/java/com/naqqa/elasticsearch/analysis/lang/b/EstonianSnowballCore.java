
package com.naqqa.elasticsearch.analysis.lang.b;


public class EstonianSnowballCore extends SnowballProgram {

  private static final long serialVersionUID = 1L;

  private static final SnowballAmong[] a_0 = {new SnowballAmong("gi", -1, 1), new SnowballAmong("ki", -1, 2)};

  private static final SnowballAmong[] a_1 = {
    new SnowballAmong("da", -1, 3),
    new SnowballAmong("mata", -1, 1),
    new SnowballAmong("b", -1, 3),
    new SnowballAmong("ksid", -1, 1),
    new SnowballAmong("nuksid", 3, 1),
    new SnowballAmong("me", -1, 3),
    new SnowballAmong("sime", 5, 1),
    new SnowballAmong("ksime", 6, 1),
    new SnowballAmong("nuksime", 7, 1),
    new SnowballAmong("akse", -1, 2),
    new SnowballAmong("dakse", 9, 1),
    new SnowballAmong("takse", 9, 1),
    new SnowballAmong("site", -1, 1),
    new SnowballAmong("ksite", 12, 1),
    new SnowballAmong("nuksite", 13, 1),
    new SnowballAmong("n", -1, 3),
    new SnowballAmong("sin", 15, 1),
    new SnowballAmong("ksin", 16, 1),
    new SnowballAmong("nuksin", 17, 1),
    new SnowballAmong("daks", -1, 1),
    new SnowballAmong("taks", -1, 1)
  };

  private static final SnowballAmong[] a_2 = {
    new SnowballAmong("aa", -1, -1),
    new SnowballAmong("ee", -1, -1),
    new SnowballAmong("ii", -1, -1),
    new SnowballAmong("oo", -1, -1),
    new SnowballAmong("uu", -1, -1),
    new SnowballAmong("\u00E4\u00E4", -1, -1),
    new SnowballAmong("\u00F5\u00F5", -1, -1),
    new SnowballAmong("\u00F6\u00F6", -1, -1),
    new SnowballAmong("\u00FC\u00FC", -1, -1)
  };

  private static final SnowballAmong[] a_3 = {
    new SnowballAmong("lane", -1, 1),
    new SnowballAmong("line", -1, 3),
    new SnowballAmong("mine", -1, 2),
    new SnowballAmong("lasse", -1, 1),
    new SnowballAmong("lisse", -1, 3),
    new SnowballAmong("misse", -1, 2),
    new SnowballAmong("lasi", -1, 1),
    new SnowballAmong("lisi", -1, 3),
    new SnowballAmong("misi", -1, 2),
    new SnowballAmong("last", -1, 1),
    new SnowballAmong("list", -1, 3),
    new SnowballAmong("mist", -1, 2)
  };

  private static final SnowballAmong[] a_4 = {
    new SnowballAmong("ga", -1, 1),
    new SnowballAmong("ta", -1, 1),
    new SnowballAmong("le", -1, 1),
    new SnowballAmong("sse", -1, 1),
    new SnowballAmong("l", -1, 1),
    new SnowballAmong("s", -1, 1),
    new SnowballAmong("ks", 5, 1),
    new SnowballAmong("t", -1, 2),
    new SnowballAmong("lt", 7, 1),
    new SnowballAmong("st", 7, 1)
  };

  private static final SnowballAmong[] a_5 = {
    new SnowballAmong("", -1, 2),
    new SnowballAmong("las", 0, 1),
    new SnowballAmong("lis", 0, 1),
    new SnowballAmong("mis", 0, 1),
    new SnowballAmong("t", 0, -1)
  };

  private static final SnowballAmong[] a_6 = {
    new SnowballAmong("d", -1, 4),
    new SnowballAmong("sid", 0, 2),
    new SnowballAmong("de", -1, 4),
    new SnowballAmong("ikkude", 2, 1),
    new SnowballAmong("ike", -1, 1),
    new SnowballAmong("ikke", -1, 1),
    new SnowballAmong("te", -1, 3)
  };

  private static final SnowballAmong[] a_7 = {
    new SnowballAmong("va", -1, -1),
    new SnowballAmong("du", -1, -1),
    new SnowballAmong("nu", -1, -1),
    new SnowballAmong("tu", -1, -1)
  };

  private static final SnowballAmong[] a_8 = {
    new SnowballAmong("kk", -1, 1), new SnowballAmong("pp", -1, 2), new SnowballAmong("tt", -1, 3)
  };

  private static final SnowballAmong[] a_9 = {
    new SnowballAmong("ma", -1, 2), new SnowballAmong("mai", -1, 1), new SnowballAmong("m", -1, 1)
  };

  private static final SnowballAmong[] a_10 = {
    new SnowballAmong("joob", -1, 1),
    new SnowballAmong("jood", -1, 1),
    new SnowballAmong("joodakse", 1, 1),
    new SnowballAmong("jooma", -1, 1),
    new SnowballAmong("joomata", 3, 1),
    new SnowballAmong("joome", -1, 1),
    new SnowballAmong("joon", -1, 1),
    new SnowballAmong("joote", -1, 1),
    new SnowballAmong("joovad", -1, 1),
    new SnowballAmong("juua", -1, 1),
    new SnowballAmong("juuakse", 9, 1),
    new SnowballAmong("j\u00E4i", -1, 12),
    new SnowballAmong("j\u00E4id", 11, 12),
    new SnowballAmong("j\u00E4ime", 11, 12),
    new SnowballAmong("j\u00E4in", 11, 12),
    new SnowballAmong("j\u00E4ite", 11, 12),
    new SnowballAmong("j\u00E4\u00E4b", -1, 12),
    new SnowballAmong("j\u00E4\u00E4d", -1, 12),
    new SnowballAmong("j\u00E4\u00E4da", 17, 12),
    new SnowballAmong("j\u00E4\u00E4dakse", 18, 12),
    new SnowballAmong("j\u00E4\u00E4di", 17, 12),
    new SnowballAmong("j\u00E4\u00E4ks", -1, 12),
    new SnowballAmong("j\u00E4\u00E4ksid", 21, 12),
    new SnowballAmong("j\u00E4\u00E4ksime", 21, 12),
    new SnowballAmong("j\u00E4\u00E4ksin", 21, 12),
    new SnowballAmong("j\u00E4\u00E4ksite", 21, 12),
    new SnowballAmong("j\u00E4\u00E4ma", -1, 12),
    new SnowballAmong("j\u00E4\u00E4mata", 26, 12),
    new SnowballAmong("j\u00E4\u00E4me", -1, 12),
    new SnowballAmong("j\u00E4\u00E4n", -1, 12),
    new SnowballAmong("j\u00E4\u00E4te", -1, 12),
    new SnowballAmong("j\u00E4\u00E4vad", -1, 12),
    new SnowballAmong("j\u00F5i", -1, 1),
    new SnowballAmong("j\u00F5id", 32, 1),
    new SnowballAmong("j\u00F5ime", 32, 1),
    new SnowballAmong("j\u00F5in", 32, 1),
    new SnowballAmong("j\u00F5ite", 32, 1),
    new SnowballAmong("keeb", -1, 4),
    new SnowballAmong("keed", -1, 4),
    new SnowballAmong("keedakse", 38, 4),
    new SnowballAmong("keeks", -1, 4),
    new SnowballAmong("keeksid", 40, 4),
    new SnowballAmong("keeksime", 40, 4),
    new SnowballAmong("keeksin", 40, 4),
    new SnowballAmong("keeksite", 40, 4),
    new SnowballAmong("keema", -1, 4),
    new SnowballAmong("keemata", 45, 4),
    new SnowballAmong("keeme", -1, 4),
    new SnowballAmong("keen", -1, 4),
    new SnowballAmong("kees", -1, 4),
    new SnowballAmong("keeta", -1, 4),
    new SnowballAmong("keete", -1, 4),
    new SnowballAmong("keevad", -1, 4),
    new SnowballAmong("k\u00E4ia", -1, 8),
    new SnowballAmong("k\u00E4iakse", 53, 8),
    new SnowballAmong("k\u00E4ib", -1, 8),
    new SnowballAmong("k\u00E4id", -1, 8),
    new SnowballAmong("k\u00E4idi", 56, 8),
    new SnowballAmong("k\u00E4iks", -1, 8),
    new SnowballAmong("k\u00E4iksid", 58, 8),
    new SnowballAmong("k\u00E4iksime", 58, 8),
    new SnowballAmong("k\u00E4iksin", 58, 8),
    new SnowballAmong("k\u00E4iksite", 58, 8),
    new SnowballAmong("k\u00E4ima", -1, 8),
    new SnowballAmong("k\u00E4imata", 63, 8),
    new SnowballAmong("k\u00E4ime", -1, 8),
    new SnowballAmong("k\u00E4in", -1, 8),
    new SnowballAmong("k\u00E4is", -1, 8),
    new SnowballAmong("k\u00E4ite", -1, 8),
    new SnowballAmong("k\u00E4ivad", -1, 8),
    new SnowballAmong("laob", -1, 16),
    new SnowballAmong("laod", -1, 16),
    new SnowballAmong("laoks", -1, 16),
    new SnowballAmong("laoksid", 72, 16),
    new SnowballAmong("laoksime", 72, 16),
    new SnowballAmong("laoksin", 72, 16),
    new SnowballAmong("laoksite", 72, 16),
    new SnowballAmong("laome", -1, 16),
    new SnowballAmong("laon", -1, 16),
    new SnowballAmong("laote", -1, 16),
    new SnowballAmong("laovad", -1, 16),
    new SnowballAmong("loeb", -1, 14),
    new SnowballAmong("loed", -1, 14),
    new SnowballAmong("loeks", -1, 14),
    new SnowballAmong("loeksid", 83, 14),
    new SnowballAmong("loeksime", 83, 14),
    new SnowballAmong("loeksin", 83, 14),
    new SnowballAmong("loeksite", 83, 14),
    new SnowballAmong("loeme", -1, 14),
    new SnowballAmong("loen", -1, 14),
    new SnowballAmong("loete", -1, 14),
    new SnowballAmong("loevad", -1, 14),
    new SnowballAmong("loob", -1, 7),
    new SnowballAmong("lood", -1, 7),
    new SnowballAmong("loodi", 93, 7),
    new SnowballAmong("looks", -1, 7),
    new SnowballAmong("looksid", 95, 7),
    new SnowballAmong("looksime", 95, 7),
    new SnowballAmong("looksin", 95, 7),
    new SnowballAmong("looksite", 95, 7),
    new SnowballAmong("looma", -1, 7),
    new SnowballAmong("loomata", 100, 7),
    new SnowballAmong("loome", -1, 7),
    new SnowballAmong("loon", -1, 7),
    new SnowballAmong("loote", -1, 7),
    new SnowballAmong("loovad", -1, 7),
    new SnowballAmong("luua", -1, 7),
    new SnowballAmong("luuakse", 106, 7),
    new SnowballAmong("l\u00F5i", -1, 6),
    new SnowballAmong("l\u00F5id", 108, 6),
    new SnowballAmong("l\u00F5ime", 108, 6),
    new SnowballAmong("l\u00F5in", 108, 6),
    new SnowballAmong("l\u00F5ite", 108, 6),
    new SnowballAmong("l\u00F6\u00F6b", -1, 5),
    new SnowballAmong("l\u00F6\u00F6d", -1, 5),
    new SnowballAmong("l\u00F6\u00F6dakse", 114, 5),
    new SnowballAmong("l\u00F6\u00F6di", 114, 5),
    new SnowballAmong("l\u00F6\u00F6ks", -1, 5),
    new SnowballAmong("l\u00F6\u00F6ksid", 117, 5),
    new SnowballAmong("l\u00F6\u00F6ksime", 117, 5),
    new SnowballAmong("l\u00F6\u00F6ksin", 117, 5),
    new SnowballAmong("l\u00F6\u00F6ksite", 117, 5),
    new SnowballAmong("l\u00F6\u00F6ma", -1, 5),
    new SnowballAmong("l\u00F6\u00F6mata", 122, 5),
    new SnowballAmong("l\u00F6\u00F6me", -1, 5),
    new SnowballAmong("l\u00F6\u00F6n", -1, 5),
    new SnowballAmong("l\u00F6\u00F6te", -1, 5),
    new SnowballAmong("l\u00F6\u00F6vad", -1, 5),
    new SnowballAmong("l\u00FC\u00FCa", -1, 5),
    new SnowballAmong("l\u00FC\u00FCakse", 128, 5),
    new SnowballAmong("m\u00FC\u00FCa", -1, 13),
    new SnowballAmong("m\u00FC\u00FCakse", 130, 13),
    new SnowballAmong("m\u00FC\u00FCb", -1, 13),
    new SnowballAmong("m\u00FC\u00FCd", -1, 13),
    new SnowballAmong("m\u00FC\u00FCdi", 133, 13),
    new SnowballAmong("m\u00FC\u00FCks", -1, 13),
    new SnowballAmong("m\u00FC\u00FCksid", 135, 13),
    new SnowballAmong("m\u00FC\u00FCksime", 135, 13),
    new SnowballAmong("m\u00FC\u00FCksin", 135, 13),
    new SnowballAmong("m\u00FC\u00FCksite", 135, 13),
    new SnowballAmong("m\u00FC\u00FCma", -1, 13),
    new SnowballAmong("m\u00FC\u00FCmata", 140, 13),
    new SnowballAmong("m\u00FC\u00FCme", -1, 13),
    new SnowballAmong("m\u00FC\u00FCn", -1, 13),
    new SnowballAmong("m\u00FC\u00FCs", -1, 13),
    new SnowballAmong("m\u00FC\u00FCte", -1, 13),
    new SnowballAmong("m\u00FC\u00FCvad", -1, 13),
    new SnowballAmong("n\u00E4eb", -1, 18),
    new SnowballAmong("n\u00E4ed", -1, 18),
    new SnowballAmong("n\u00E4eks", -1, 18),
    new SnowballAmong("n\u00E4eksid", 149, 18),
    new SnowballAmong("n\u00E4eksime", 149, 18),
    new SnowballAmong("n\u00E4eksin", 149, 18),
    new SnowballAmong("n\u00E4eksite", 149, 18),
    new SnowballAmong("n\u00E4eme", -1, 18),
    new SnowballAmong("n\u00E4en", -1, 18),
    new SnowballAmong("n\u00E4ete", -1, 18),
    new SnowballAmong("n\u00E4evad", -1, 18),
    new SnowballAmong("n\u00E4gema", -1, 18),
    new SnowballAmong("n\u00E4gemata", 158, 18),
    new SnowballAmong("n\u00E4ha", -1, 18),
    new SnowballAmong("n\u00E4hakse", 160, 18),
    new SnowballAmong("n\u00E4hti", -1, 18),
    new SnowballAmong("p\u00F5eb", -1, 15),
    new SnowballAmong("p\u00F5ed", -1, 15),
    new SnowballAmong("p\u00F5eks", -1, 15),
    new SnowballAmong("p\u00F5eksid", 165, 15),
    new SnowballAmong("p\u00F5eksime", 165, 15),
    new SnowballAmong("p\u00F5eksin", 165, 15),
    new SnowballAmong("p\u00F5eksite", 165, 15),
    new SnowballAmong("p\u00F5eme", -1, 15),
    new SnowballAmong("p\u00F5en", -1, 15),
    new SnowballAmong("p\u00F5ete", -1, 15),
    new SnowballAmong("p\u00F5evad", -1, 15),
    new SnowballAmong("saab", -1, 2),
    new SnowballAmong("saad", -1, 2),
    new SnowballAmong("saada", 175, 2),
    new SnowballAmong("saadakse", 176, 2),
    new SnowballAmong("saadi", 175, 2),
    new SnowballAmong("saaks", -1, 2),
    new SnowballAmong("saaksid", 179, 2),
    new SnowballAmong("saaksime", 179, 2),
    new SnowballAmong("saaksin", 179, 2),
    new SnowballAmong("saaksite", 179, 2),
    new SnowballAmong("saama", -1, 2),
    new SnowballAmong("saamata", 184, 2),
    new SnowballAmong("saame", -1, 2),
    new SnowballAmong("saan", -1, 2),
    new SnowballAmong("saate", -1, 2),
    new SnowballAmong("saavad", -1, 2),
    new SnowballAmong("sai", -1, 2),
    new SnowballAmong("said", 190, 2),
    new SnowballAmong("saime", 190, 2),
    new SnowballAmong("sain", 190, 2),
    new SnowballAmong("saite", 190, 2),
    new SnowballAmong("s\u00F5i", -1, 9),
    new SnowballAmong("s\u00F5id", 195, 9),
    new SnowballAmong("s\u00F5ime", 195, 9),
    new SnowballAmong("s\u00F5in", 195, 9),
    new SnowballAmong("s\u00F5ite", 195, 9),
    new SnowballAmong("s\u00F6\u00F6b", -1, 9),
    new SnowballAmong("s\u00F6\u00F6d", -1, 9),
    new SnowballAmong("s\u00F6\u00F6dakse", 201, 9),
    new SnowballAmong("s\u00F6\u00F6di", 201, 9),
    new SnowballAmong("s\u00F6\u00F6ks", -1, 9),
    new SnowballAmong("s\u00F6\u00F6ksid", 204, 9),
    new SnowballAmong("s\u00F6\u00F6ksime", 204, 9),
    new SnowballAmong("s\u00F6\u00F6ksin", 204, 9),
    new SnowballAmong("s\u00F6\u00F6ksite", 204, 9),
    new SnowballAmong("s\u00F6\u00F6ma", -1, 9),
    new SnowballAmong("s\u00F6\u00F6mata", 209, 9),
    new SnowballAmong("s\u00F6\u00F6me", -1, 9),
    new SnowballAmong("s\u00F6\u00F6n", -1, 9),
    new SnowballAmong("s\u00F6\u00F6te", -1, 9),
    new SnowballAmong("s\u00F6\u00F6vad", -1, 9),
    new SnowballAmong("s\u00FC\u00FCa", -1, 9),
    new SnowballAmong("s\u00FC\u00FCakse", 215, 9),
    new SnowballAmong("teeb", -1, 17),
    new SnowballAmong("teed", -1, 17),
    new SnowballAmong("teeks", -1, 17),
    new SnowballAmong("teeksid", 219, 17),
    new SnowballAmong("teeksime", 219, 17),
    new SnowballAmong("teeksin", 219, 17),
    new SnowballAmong("teeksite", 219, 17),
    new SnowballAmong("teeme", -1, 17),
    new SnowballAmong("teen", -1, 17),
    new SnowballAmong("teete", -1, 17),
    new SnowballAmong("teevad", -1, 17),
    new SnowballAmong("tegema", -1, 17),
    new SnowballAmong("tegemata", 228, 17),
    new SnowballAmong("teha", -1, 17),
    new SnowballAmong("tehakse", 230, 17),
    new SnowballAmong("tehti", -1, 17),
    new SnowballAmong("toob", -1, 10),
    new SnowballAmong("tood", -1, 10),
    new SnowballAmong("toodi", 234, 10),
    new SnowballAmong("tooks", -1, 10),
    new SnowballAmong("tooksid", 236, 10),
    new SnowballAmong("tooksime", 236, 10),
    new SnowballAmong("tooksin", 236, 10),
    new SnowballAmong("tooksite", 236, 10),
    new SnowballAmong("tooma", -1, 10),
    new SnowballAmong("toomata", 241, 10),
    new SnowballAmong("toome", -1, 10),
    new SnowballAmong("toon", -1, 10),
    new SnowballAmong("toote", -1, 10),
    new SnowballAmong("toovad", -1, 10),
    new SnowballAmong("tuua", -1, 10),
    new SnowballAmong("tuuakse", 247, 10),
    new SnowballAmong("t\u00F5i", -1, 10),
    new SnowballAmong("t\u00F5id", 249, 10),
    new SnowballAmong("t\u00F5ime", 249, 10),
    new SnowballAmong("t\u00F5in", 249, 10),
    new SnowballAmong("t\u00F5ite", 249, 10),
    new SnowballAmong("viia", -1, 3),
    new SnowballAmong("viiakse", 254, 3),
    new SnowballAmong("viib", -1, 3),
    new SnowballAmong("viid", -1, 3),
    new SnowballAmong("viidi", 257, 3),
    new SnowballAmong("viiks", -1, 3),
    new SnowballAmong("viiksid", 259, 3),
    new SnowballAmong("viiksime", 259, 3),
    new SnowballAmong("viiksin", 259, 3),
    new SnowballAmong("viiksite", 259, 3),
    new SnowballAmong("viima", -1, 3),
    new SnowballAmong("viimata", 264, 3),
    new SnowballAmong("viime", -1, 3),
    new SnowballAmong("viin", -1, 3),
    new SnowballAmong("viisime", -1, 3),
    new SnowballAmong("viisin", -1, 3),
    new SnowballAmong("viisite", -1, 3),
    new SnowballAmong("viite", -1, 3),
    new SnowballAmong("viivad", -1, 3),
    new SnowballAmong("v\u00F5ib", -1, 11),
    new SnowballAmong("v\u00F5id", -1, 11),
    new SnowballAmong("v\u00F5ida", 274, 11),
    new SnowballAmong("v\u00F5idakse", 275, 11),
    new SnowballAmong("v\u00F5idi", 274, 11),
    new SnowballAmong("v\u00F5iks", -1, 11),
    new SnowballAmong("v\u00F5iksid", 278, 11),
    new SnowballAmong("v\u00F5iksime", 278, 11),
    new SnowballAmong("v\u00F5iksin", 278, 11),
    new SnowballAmong("v\u00F5iksite", 278, 11),
    new SnowballAmong("v\u00F5ima", -1, 11),
    new SnowballAmong("v\u00F5imata", 283, 11),
    new SnowballAmong("v\u00F5ime", -1, 11),
    new SnowballAmong("v\u00F5in", -1, 11),
    new SnowballAmong("v\u00F5is", -1, 11),
    new SnowballAmong("v\u00F5ite", -1, 11),
    new SnowballAmong("v\u00F5ivad", -1, 11)
  };

  private static final char[] g_V1 = {
    17, 65, 16, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 8, 0, 48, 8
  };

  private static final char[] g_RV = {17, 65, 16};

  private static final char[] g_KI = {
    117, 66, 6, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    128, 0, 0, 0, 16
  };

  private static final char[] g_GI = {
    21, 123, 243, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 8, 0, 48, 8
  };

  private int I_p1;

  private boolean r_mark_regions() {
    I_p1 = limit;
    if (!go_out_grouping(g_V1, 97, 252)) {
      return false;
    }
    cursor++;
    if (!go_in_grouping(g_V1, 97, 252)) {
      return false;
    }
    cursor++;
    I_p1 = cursor;
    return true;
  }

  private boolean r_emphasis() {
    int among_var;
    if (cursor < I_p1) {
      return false;
    }
    int v_1 = limit_backward;
    limit_backward = I_p1;
    ket = cursor;
    among_var = find_among_b(a_0);
    if (among_var == 0) {
      limit_backward = v_1;
      return false;
    }
    bra = cursor;
    limit_backward = v_1;
    int v_2 = limit - cursor;
    {
      int c = cursor - 4;
      if (c < limit_backward) {
        return false;
      }
      cursor = c;
    }
    cursor = limit - v_2;
    switch (among_var) {
      case 1:
        int v_3 = limit - cursor;
        if (!(in_grouping_b(g_GI, 97, 252))) {
          return false;
        }
        cursor = limit - v_3;
        {
          int v_4 = limit - cursor;
          lab0:
          {
            if (!r_LONGV()) {
              break lab0;
            }
            return false;
          }
          cursor = limit - v_4;
        }
        slice_del();
        break;
      case 2:
        if (!(in_grouping_b(g_KI, 98, 382))) {
          return false;
        }
        slice_del();
        break;
    }
    return true;
  }

  private boolean r_verb() {
    int among_var;
    if (cursor < I_p1) {
      return false;
    }
    int v_1 = limit_backward;
    limit_backward = I_p1;
    ket = cursor;
    among_var = find_among_b(a_1);
    if (among_var == 0) {
      limit_backward = v_1;
      return false;
    }
    bra = cursor;
    limit_backward = v_1;
    switch (among_var) {
      case 1:
        slice_del();
        break;
      case 2:
        slice_from("a");
        break;
      case 3:
        if (!(in_grouping_b(g_V1, 97, 252))) {
          return false;
        }
        slice_del();
        break;
    }
    return true;
  }

  private boolean r_LONGV() {
    return find_among_b(a_2) != 0;
  }

  private boolean r_i_plural() {
    if (cursor < I_p1) {
      return false;
    }
    int v_1 = limit_backward;
    limit_backward = I_p1;
    ket = cursor;
    if (!(eq_s_b("i"))) {
      limit_backward = v_1;
      return false;
    }
    bra = cursor;
    limit_backward = v_1;
    if (!(in_grouping_b(g_RV, 97, 117))) {
      return false;
    }
    slice_del();
    return true;
  }

  private boolean r_special_noun_endings() {
    int among_var;
    if (cursor < I_p1) {
      return false;
    }
    int v_1 = limit_backward;
    limit_backward = I_p1;
    ket = cursor;
    among_var = find_among_b(a_3);
    if (among_var == 0) {
      limit_backward = v_1;
      return false;
    }
    bra = cursor;
    limit_backward = v_1;
    switch (among_var) {
      case 1:
        slice_from("lase");
        break;
      case 2:
        slice_from("mise");
        break;
      case 3:
        slice_from("lise");
        break;
    }
    return true;
  }

  private boolean r_case_ending() {
    int among_var;
    if (cursor < I_p1) {
      return false;
    }
    int v_1 = limit_backward;
    limit_backward = I_p1;
    ket = cursor;
    among_var = find_among_b(a_4);
    if (among_var == 0) {
      limit_backward = v_1;
      return false;
    }
    bra = cursor;
    limit_backward = v_1;
    switch (among_var) {
      case 1:
        lab0:
        {
          int v_2 = limit - cursor;
          lab1:
          {
            if (!(in_grouping_b(g_RV, 97, 117))) {
              break lab1;
            }
            break lab0;
          }
          cursor = limit - v_2;
          if (!r_LONGV()) {
            return false;
          }
        }
        break;
      case 2:
        int v_3 = limit - cursor;
        {
          int c = cursor - 4;
          if (c < limit_backward) {
            return false;
          }
          cursor = c;
        }
        cursor = limit - v_3;
        break;
    }
    slice_del();
    return true;
  }

  private boolean r_plural_three_first_cases() {
    int among_var;
    if (cursor < I_p1) {
      return false;
    }
    int v_1 = limit_backward;
    limit_backward = I_p1;
    ket = cursor;
    among_var = find_among_b(a_6);
    if (among_var == 0) {
      limit_backward = v_1;
      return false;
    }
    bra = cursor;
    limit_backward = v_1;
    switch (among_var) {
      case 1:
        slice_from("iku");
        break;
      case 2:
        {
          int v_2 = limit - cursor;
          lab0:
          {
            if (!r_LONGV()) {
              break lab0;
            }
            return false;
          }
          cursor = limit - v_2;
        }
        slice_del();
        break;
      case 3:
        lab1:
        {
          int v_3 = limit - cursor;
          lab2:
          {
            int v_4 = limit - cursor;
            {
              int c = cursor - 4;
              if (c < limit_backward) {
                break lab2;
              }
              cursor = c;
            }
            cursor = limit - v_4;
            among_var = find_among_b(a_5);
            switch (among_var) {
              case 1:
                slice_from("e");
                break;
              case 2:
                slice_del();
                break;
            }
            break lab1;
          }
          cursor = limit - v_3;
          slice_from("t");
        }
        break;
      case 4:
        lab3:
        {
          int v_5 = limit - cursor;
          lab4:
          {
            if (!(in_grouping_b(g_RV, 97, 117))) {
              break lab4;
            }
            break lab3;
          }
          cursor = limit - v_5;
          if (!r_LONGV()) {
            return false;
          }
        }
        slice_del();
        break;
    }
    return true;
  }

  private boolean r_nu() {
    if (cursor < I_p1) {
      return false;
    }
    int v_1 = limit_backward;
    limit_backward = I_p1;
    ket = cursor;
    if (find_among_b(a_7) == 0) {
      limit_backward = v_1;
      return false;
    }
    bra = cursor;
    limit_backward = v_1;
    slice_del();
    return true;
  }

  private boolean r_undouble_kpt() {
    int among_var;
    if (!(in_grouping_b(g_V1, 97, 252))) {
      return false;
    }
    if (I_p1 > cursor) {
      return false;
    }
    ket = cursor;
    among_var = find_among_b(a_8);
    if (among_var == 0) {
      return false;
    }
    bra = cursor;
    switch (among_var) {
      case 1:
        slice_from("k");
        break;
      case 2:
        slice_from("p");
        break;
      case 3:
        slice_from("t");
        break;
    }
    return true;
  }

  private boolean r_degrees() {
    int among_var;
    if (cursor < I_p1) {
      return false;
    }
    int v_1 = limit_backward;
    limit_backward = I_p1;
    ket = cursor;
    among_var = find_among_b(a_9);
    if (among_var == 0) {
      limit_backward = v_1;
      return false;
    }
    bra = cursor;
    limit_backward = v_1;
    switch (among_var) {
      case 1:
        if (!(in_grouping_b(g_RV, 97, 117))) {
          return false;
        }
        slice_del();
        break;
      case 2:
        slice_del();
        break;
    }
    return true;
  }

  private boolean r_substantive() {
    int v_1 = limit - cursor;
    r_special_noun_endings();
    cursor = limit - v_1;
    int v_2 = limit - cursor;
    r_case_ending();
    cursor = limit - v_2;
    int v_3 = limit - cursor;
    r_plural_three_first_cases();
    cursor = limit - v_3;
    int v_4 = limit - cursor;
    r_degrees();
    cursor = limit - v_4;
    int v_5 = limit - cursor;
    r_i_plural();
    cursor = limit - v_5;
    int v_6 = limit - cursor;
    r_nu();
    cursor = limit - v_6;
    return true;
  }

  private boolean r_verb_exceptions() {
    int among_var;
    bra = cursor;
    among_var = find_among(a_10);
    if (among_var == 0) {
      return false;
    }
    ket = cursor;
    if (cursor < limit) {
      return false;
    }
    switch (among_var) {
      case 1:
        slice_from("joo");
        break;
      case 2:
        slice_from("saa");
        break;
      case 3:
        slice_from("viima");
        break;
      case 4:
        slice_from("keesi");
        break;
      case 5:
        slice_from("l\u00F6\u00F6");
        break;
      case 6:
        slice_from("l\u00F5i");
        break;
      case 7:
        slice_from("loo");
        break;
      case 8:
        slice_from("k\u00E4isi");
        break;
      case 9:
        slice_from("s\u00F6\u00F6");
        break;
      case 10:
        slice_from("too");
        break;
      case 11:
        slice_from("v\u00F5isi");
        break;
      case 12:
        slice_from("j\u00E4\u00E4ma");
        break;
      case 13:
        slice_from("m\u00FC\u00FCsi");
        break;
      case 14:
        slice_from("luge");
        break;
      case 15:
        slice_from("p\u00F5de");
        break;
      case 16:
        slice_from("ladu");
        break;
      case 17:
        slice_from("tegi");
        break;
      case 18:
        slice_from("n\u00E4gi");
        break;
    }
    return true;
  }

  @Override
  public boolean stem() {
    {
      int v_1 = cursor;
      lab0:
      {
        if (!r_verb_exceptions()) {
          break lab0;
        }
        return false;
      }
      cursor = v_1;
    }
    int v_2 = cursor;
    r_mark_regions();
    cursor = v_2;
    limit_backward = cursor;
    cursor = limit;
    int v_3 = limit - cursor;
    r_emphasis();
    cursor = limit - v_3;
    int v_4 = limit - cursor;
    lab1:
    {
      lab2:
      {
        int v_5 = limit - cursor;
        lab3:
        {
          if (!r_verb()) {
            break lab3;
          }
          break lab2;
        }
        cursor = limit - v_5;
        r_substantive();
      }
    }
    cursor = limit - v_4;
    int v_6 = limit - cursor;
    r_undouble_kpt();
    cursor = limit - v_6;
    cursor = limit_backward;
    return true;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof EstonianSnowballCore;
  }

  @Override
  public int hashCode() {
    return EstonianSnowballCore.class.getName().hashCode();
  }
}
