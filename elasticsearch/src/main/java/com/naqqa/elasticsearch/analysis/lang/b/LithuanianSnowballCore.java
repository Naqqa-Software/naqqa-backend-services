
package com.naqqa.elasticsearch.analysis.lang.b;


public class LithuanianSnowballCore extends SnowballProgram {

  private static final long serialVersionUID = 1L;

  private static final SnowballAmong[] a_0 = {
    new SnowballAmong("a", -1, -1),
    new SnowballAmong("ia", 0, -1),
    new SnowballAmong("osna", 0, -1),
    new SnowballAmong("iosna", 2, -1),
    new SnowballAmong("uosna", 2, -1),
    new SnowballAmong("iuosna", 4, -1),
    new SnowballAmong("ysna", 0, -1),
    new SnowballAmong("\u0117sna", 0, -1),
    new SnowballAmong("e", -1, -1),
    new SnowballAmong("ie", 8, -1),
    new SnowballAmong("enie", 9, -1),
    new SnowballAmong("oje", 8, -1),
    new SnowballAmong("ioje", 11, -1),
    new SnowballAmong("uje", 8, -1),
    new SnowballAmong("iuje", 13, -1),
    new SnowballAmong("yje", 8, -1),
    new SnowballAmong("enyje", 15, -1),
    new SnowballAmong("\u0117je", 8, -1),
    new SnowballAmong("ame", 8, -1),
    new SnowballAmong("iame", 18, -1),
    new SnowballAmong("sime", 8, -1),
    new SnowballAmong("ome", 8, -1),
    new SnowballAmong("\u0117me", 8, -1),
    new SnowballAmong("tum\u0117me", 22, -1),
    new SnowballAmong("ose", 8, -1),
    new SnowballAmong("iose", 24, -1),
    new SnowballAmong("uose", 24, -1),
    new SnowballAmong("iuose", 26, -1),
    new SnowballAmong("yse", 8, -1),
    new SnowballAmong("enyse", 28, -1),
    new SnowballAmong("\u0117se", 8, -1),
    new SnowballAmong("ate", 8, -1),
    new SnowballAmong("iate", 31, -1),
    new SnowballAmong("ite", 8, -1),
    new SnowballAmong("kite", 33, -1),
    new SnowballAmong("site", 33, -1),
    new SnowballAmong("ote", 8, -1),
    new SnowballAmong("tute", 8, -1),
    new SnowballAmong("\u0117te", 8, -1),
    new SnowballAmong("tum\u0117te", 38, -1),
    new SnowballAmong("i", -1, -1),
    new SnowballAmong("ai", 40, -1),
    new SnowballAmong("iai", 41, -1),
    new SnowballAmong("ei", 40, -1),
    new SnowballAmong("tumei", 43, -1),
    new SnowballAmong("ki", 40, -1),
    new SnowballAmong("imi", 40, -1),
    new SnowballAmong("umi", 40, -1),
    new SnowballAmong("iumi", 47, -1),
    new SnowballAmong("si", 40, -1),
    new SnowballAmong("asi", 49, -1),
    new SnowballAmong("iasi", 50, -1),
    new SnowballAmong("esi", 49, -1),
    new SnowballAmong("iesi", 52, -1),
    new SnowballAmong("siesi", 53, -1),
    new SnowballAmong("isi", 49, -1),
    new SnowballAmong("aisi", 55, -1),
    new SnowballAmong("eisi", 55, -1),
    new SnowballAmong("tumeisi", 57, -1),
    new SnowballAmong("uisi", 55, -1),
    new SnowballAmong("osi", 49, -1),
    new SnowballAmong("\u0117josi", 60, -1),
    new SnowballAmong("uosi", 60, -1),
    new SnowballAmong("iuosi", 62, -1),
    new SnowballAmong("siuosi", 63, -1),
    new SnowballAmong("usi", 49, -1),
    new SnowballAmong("ausi", 65, -1),
    new SnowballAmong("\u010Diausi", 66, -1),
    new SnowballAmong("\u0105si", 49, -1),
    new SnowballAmong("\u0117si", 49, -1),
    new SnowballAmong("\u0173si", 49, -1),
    new SnowballAmong("t\u0173si", 70, -1),
    new SnowballAmong("ti", 40, -1),
    new SnowballAmong("enti", 72, -1),
    new SnowballAmong("inti", 72, -1),
    new SnowballAmong("oti", 72, -1),
    new SnowballAmong("ioti", 75, -1),
    new SnowballAmong("uoti", 75, -1),
    new SnowballAmong("iuoti", 77, -1),
    new SnowballAmong("auti", 72, -1),
    new SnowballAmong("iauti", 79, -1),
    new SnowballAmong("yti", 72, -1),
    new SnowballAmong("\u0117ti", 72, -1),
    new SnowballAmong("tel\u0117ti", 82, -1),
    new SnowballAmong("in\u0117ti", 82, -1),
    new SnowballAmong("ter\u0117ti", 82, -1),
    new SnowballAmong("ui", 40, -1),
    new SnowballAmong("iui", 86, -1),
    new SnowballAmong("eniui", 87, -1),
    new SnowballAmong("oj", -1, -1),
    new SnowballAmong("\u0117j", -1, -1),
    new SnowballAmong("k", -1, -1),
    new SnowballAmong("am", -1, -1),
    new SnowballAmong("iam", 92, -1),
    new SnowballAmong("iem", -1, -1),
    new SnowballAmong("im", -1, -1),
    new SnowballAmong("sim", 95, -1),
    new SnowballAmong("om", -1, -1),
    new SnowballAmong("tum", -1, -1),
    new SnowballAmong("\u0117m", -1, -1),
    new SnowballAmong("tum\u0117m", 99, -1),
    new SnowballAmong("an", -1, -1),
    new SnowballAmong("on", -1, -1),
    new SnowballAmong("ion", 102, -1),
    new SnowballAmong("un", -1, -1),
    new SnowballAmong("iun", 104, -1),
    new SnowballAmong("\u0117n", -1, -1),
    new SnowballAmong("o", -1, -1),
    new SnowballAmong("io", 107, -1),
    new SnowballAmong("enio", 108, -1),
    new SnowballAmong("\u0117jo", 107, -1),
    new SnowballAmong("uo", 107, -1),
    new SnowballAmong("s", -1, -1),
    new SnowballAmong("as", 112, -1),
    new SnowballAmong("ias", 113, -1),
    new SnowballAmong("es", 112, -1),
    new SnowballAmong("ies", 115, -1),
    new SnowballAmong("is", 112, -1),
    new SnowballAmong("ais", 117, -1),
    new SnowballAmong("iais", 118, -1),
    new SnowballAmong("tumeis", 117, -1),
    new SnowballAmong("imis", 117, -1),
    new SnowballAmong("enimis", 121, -1),
    new SnowballAmong("omis", 117, -1),
    new SnowballAmong("iomis", 123, -1),
    new SnowballAmong("umis", 117, -1),
    new SnowballAmong("\u0117mis", 117, -1),
    new SnowballAmong("enis", 117, -1),
    new SnowballAmong("asis", 117, -1),
    new SnowballAmong("ysis", 117, -1),
    new SnowballAmong("ams", 112, -1),
    new SnowballAmong("iams", 130, -1),
    new SnowballAmong("iems", 112, -1),
    new SnowballAmong("ims", 112, -1),
    new SnowballAmong("enims", 133, -1),
    new SnowballAmong("oms", 112, -1),
    new SnowballAmong("ioms", 135, -1),
    new SnowballAmong("ums", 112, -1),
    new SnowballAmong("\u0117ms", 112, -1),
    new SnowballAmong("ens", 112, -1),
    new SnowballAmong("os", 112, -1),
    new SnowballAmong("ios", 140, -1),
    new SnowballAmong("uos", 140, -1),
    new SnowballAmong("iuos", 142, -1),
    new SnowballAmong("us", 112, -1),
    new SnowballAmong("aus", 144, -1),
    new SnowballAmong("iaus", 145, -1),
    new SnowballAmong("ius", 144, -1),
    new SnowballAmong("ys", 112, -1),
    new SnowballAmong("enys", 148, -1),
    new SnowballAmong("\u0105s", 112, -1),
    new SnowballAmong("i\u0105s", 150, -1),
    new SnowballAmong("\u0117s", 112, -1),
    new SnowballAmong("am\u0117s", 152, -1),
    new SnowballAmong("iam\u0117s", 153, -1),
    new SnowballAmong("im\u0117s", 152, -1),
    new SnowballAmong("kim\u0117s", 155, -1),
    new SnowballAmong("sim\u0117s", 155, -1),
    new SnowballAmong("om\u0117s", 152, -1),
    new SnowballAmong("\u0117m\u0117s", 152, -1),
    new SnowballAmong("tum\u0117m\u0117s", 159, -1),
    new SnowballAmong("at\u0117s", 152, -1),
    new SnowballAmong("iat\u0117s", 161, -1),
    new SnowballAmong("sit\u0117s", 152, -1),
    new SnowballAmong("ot\u0117s", 152, -1),
    new SnowballAmong("\u0117t\u0117s", 152, -1),
    new SnowballAmong("tum\u0117t\u0117s", 165, -1),
    new SnowballAmong("\u012Fs", 112, -1),
    new SnowballAmong("\u016Bs", 112, -1),
    new SnowballAmong("t\u0173s", 112, -1),
    new SnowballAmong("at", -1, -1),
    new SnowballAmong("iat", 170, -1),
    new SnowballAmong("it", -1, -1),
    new SnowballAmong("sit", 172, -1),
    new SnowballAmong("ot", -1, -1),
    new SnowballAmong("\u0117t", -1, -1),
    new SnowballAmong("tum\u0117t", 175, -1),
    new SnowballAmong("u", -1, -1),
    new SnowballAmong("au", 177, -1),
    new SnowballAmong("iau", 178, -1),
    new SnowballAmong("\u010Diau", 179, -1),
    new SnowballAmong("iu", 177, -1),
    new SnowballAmong("eniu", 181, -1),
    new SnowballAmong("siu", 181, -1),
    new SnowballAmong("y", -1, -1),
    new SnowballAmong("\u0105", -1, -1),
    new SnowballAmong("i\u0105", 185, -1),
    new SnowballAmong("\u0117", -1, -1),
    new SnowballAmong("\u0119", -1, -1),
    new SnowballAmong("\u012F", -1, -1),
    new SnowballAmong("en\u012F", 189, -1),
    new SnowballAmong("\u0173", -1, -1),
    new SnowballAmong("i\u0173", 191, -1)
  };

  private static final SnowballAmong[] a_1 = {
    new SnowballAmong("ing", -1, -1),
    new SnowballAmong("aj", -1, -1),
    new SnowballAmong("iaj", 1, -1),
    new SnowballAmong("iej", -1, -1),
    new SnowballAmong("oj", -1, -1),
    new SnowballAmong("ioj", 4, -1),
    new SnowballAmong("uoj", 4, -1),
    new SnowballAmong("iuoj", 6, -1),
    new SnowballAmong("auj", -1, -1),
    new SnowballAmong("\u0105j", -1, -1),
    new SnowballAmong("i\u0105j", 9, -1),
    new SnowballAmong("\u0117j", -1, -1),
    new SnowballAmong("\u0173j", -1, -1),
    new SnowballAmong("i\u0173j", 12, -1),
    new SnowballAmong("ok", -1, -1),
    new SnowballAmong("iok", 14, -1),
    new SnowballAmong("iuk", -1, -1),
    new SnowballAmong("uliuk", 16, -1),
    new SnowballAmong("u\u010Diuk", 16, -1),
    new SnowballAmong("i\u0161k", -1, -1),
    new SnowballAmong("iul", -1, -1),
    new SnowballAmong("yl", -1, -1),
    new SnowballAmong("\u0117l", -1, -1),
    new SnowballAmong("am", -1, -1),
    new SnowballAmong("dam", 23, -1),
    new SnowballAmong("jam", 23, -1),
    new SnowballAmong("zgan", -1, -1),
    new SnowballAmong("ain", -1, -1),
    new SnowballAmong("esn", -1, -1),
    new SnowballAmong("op", -1, -1),
    new SnowballAmong("iop", 29, -1),
    new SnowballAmong("ias", -1, -1),
    new SnowballAmong("ies", -1, -1),
    new SnowballAmong("ais", -1, -1),
    new SnowballAmong("iais", 33, -1),
    new SnowballAmong("os", -1, -1),
    new SnowballAmong("ios", 35, -1),
    new SnowballAmong("uos", 35, -1),
    new SnowballAmong("iuos", 37, -1),
    new SnowballAmong("aus", -1, -1),
    new SnowballAmong("iaus", 39, -1),
    new SnowballAmong("\u0105s", -1, -1),
    new SnowballAmong("i\u0105s", 41, -1),
    new SnowballAmong("\u0119s", -1, -1),
    new SnowballAmong("ut\u0117ait", -1, -1),
    new SnowballAmong("ant", -1, -1),
    new SnowballAmong("iant", 45, -1),
    new SnowballAmong("siant", 46, -1),
    new SnowballAmong("int", -1, -1),
    new SnowballAmong("ot", -1, -1),
    new SnowballAmong("uot", 49, -1),
    new SnowballAmong("iuot", 50, -1),
    new SnowballAmong("yt", -1, -1),
    new SnowballAmong("\u0117t", -1, -1),
    new SnowballAmong("yk\u0161t", -1, -1),
    new SnowballAmong("iau", -1, -1),
    new SnowballAmong("dav", -1, -1),
    new SnowballAmong("sv", -1, -1),
    new SnowballAmong("\u0161v", -1, -1),
    new SnowballAmong("yk\u0161\u010D", -1, -1),
    new SnowballAmong("\u0119", -1, -1),
    new SnowballAmong("\u0117j\u0119", 60, -1)
  };

  private static final SnowballAmong[] a_2 = {
    new SnowballAmong("ojime", -1, 7),
    new SnowballAmong("\u0117jime", -1, 3),
    new SnowballAmong("avime", -1, 6),
    new SnowballAmong("okate", -1, 8),
    new SnowballAmong("aite", -1, 1),
    new SnowballAmong("uote", -1, 2),
    new SnowballAmong("asius", -1, 5),
    new SnowballAmong("okat\u0117s", -1, 8),
    new SnowballAmong("ait\u0117s", -1, 1),
    new SnowballAmong("uot\u0117s", -1, 2),
    new SnowballAmong("esiu", -1, 4)
  };

  private static final SnowballAmong[] a_3 = {new SnowballAmong("\u010D", -1, 1), new SnowballAmong("d\u017E", -1, 2)};

  private static final char[] g_v = {
    17, 65, 16, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 16, 0, 64, 1, 0, 64, 0, 0, 0, 0,
    0, 0, 0, 4, 4
  };

  private int I_p1;

  private boolean r_step1() {
    if (cursor < I_p1) {
      return false;
    }
    int v_1 = limit_backward;
    limit_backward = I_p1;
    ket = cursor;
    if (find_among_b(a_0) == 0) {
      limit_backward = v_1;
      return false;
    }
    bra = cursor;
    limit_backward = v_1;
    slice_del();
    return true;
  }

  private boolean r_step2() {
    while (true) {
      int v_1 = limit - cursor;
      lab0:
      {
        if (cursor < I_p1) {
          break lab0;
        }
        int v_2 = limit_backward;
        limit_backward = I_p1;
        ket = cursor;
        if (find_among_b(a_1) == 0) {
          limit_backward = v_2;
          break lab0;
        }
        bra = cursor;
        limit_backward = v_2;
        slice_del();
        continue;
      }
      cursor = limit - v_1;
      break;
    }
    return true;
  }

  private boolean r_fix_conflicts() {
    int among_var;
    ket = cursor;
    among_var = find_among_b(a_2);
    if (among_var == 0) {
      return false;
    }
    bra = cursor;
    switch (among_var) {
      case 1:
        slice_from("ait\u0117");
        break;
      case 2:
        slice_from("uot\u0117");
        break;
      case 3:
        slice_from("\u0117jimas");
        break;
      case 4:
        slice_from("esys");
        break;
      case 5:
        slice_from("asys");
        break;
      case 6:
        slice_from("avimas");
        break;
      case 7:
        slice_from("ojimas");
        break;
      case 8:
        slice_from("okat\u0117");
        break;
    }
    return true;
  }

  private boolean r_fix_chdz() {
    int among_var;
    ket = cursor;
    among_var = find_among_b(a_3);
    if (among_var == 0) {
      return false;
    }
    bra = cursor;
    switch (among_var) {
      case 1:
        slice_from("t");
        break;
      case 2:
        slice_from("d");
        break;
    }
    return true;
  }

  private boolean r_fix_gd() {
    ket = cursor;
    if (!(eq_s_b("gd"))) {
      return false;
    }
    bra = cursor;
    slice_from("g");
    return true;
  }

  @Override
  public boolean stem() {
    I_p1 = limit;
    int v_1 = cursor;
    lab0:
    {
      int v_2 = cursor;
      lab1:
      {
        if (!(eq_s("a"))) {
          cursor = v_2;
          break lab1;
        }
        if (length <= 6) {
          cursor = v_2;
          break lab1;
        }
      }
      if (!go_out_grouping(g_v, 97, 371)) {
        break lab0;
      }
      cursor++;
      if (!go_in_grouping(g_v, 97, 371)) {
        break lab0;
      }
      cursor++;
      I_p1 = cursor;
    }
    cursor = v_1;
    limit_backward = cursor;
    cursor = limit;
    int v_3 = limit - cursor;
    r_fix_conflicts();
    cursor = limit - v_3;
    int v_4 = limit - cursor;
    r_step1();
    cursor = limit - v_4;
    int v_5 = limit - cursor;
    r_fix_chdz();
    cursor = limit - v_5;
    int v_6 = limit - cursor;
    r_step2();
    cursor = limit - v_6;
    int v_7 = limit - cursor;
    r_fix_chdz();
    cursor = limit - v_7;
    int v_8 = limit - cursor;
    r_fix_gd();
    cursor = limit - v_8;
    cursor = limit_backward;
    return true;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof LithuanianSnowballCore;
  }

  @Override
  public int hashCode() {
    return LithuanianSnowballCore.class.getName().hashCode();
  }
}
