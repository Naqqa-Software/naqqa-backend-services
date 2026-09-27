package com.naqqa.elasticsearch.analysis.phonetic;

import java.util.Locale;

public class Caverphone2 implements PhoneticEncoder {

    private static final String TEN_1 = "1111111111";

    @Override
    public String encode(String source) {
        if (source == null) {
            return null;
        }
        String txt = source;
        if (txt.isEmpty()) {
            return TEN_1;
        }
        txt = txt.toLowerCase(Locale.ENGLISH);
        txt = txt.replaceAll("[^a-z]", "");
        txt = txt.replaceAll("e$", "");
        txt = txt.replaceAll("^cough", "cou2f");
        txt = txt.replaceAll("^rough", "rou2f");
        txt = txt.replaceAll("^tough", "tou2f");
        txt = txt.replaceAll("^enough", "enou2f");
        txt = txt.replaceAll("^trough", "trou2f");
        txt = txt.replaceAll("^gn", "2n");
        txt = txt.replaceAll("mb$", "m2");
        txt = txt.replace("cq", "2q");
        txt = txt.replace("ci", "si");
        txt = txt.replace("ce", "se");
        txt = txt.replace("cy", "sy");
        txt = txt.replace("tch", "2ch");
        txt = txt.replace("c", "k");
        txt = txt.replace("q", "k");
        txt = txt.replace("x", "k");
        txt = txt.replace("v", "f");
        txt = txt.replace("dg", "2g");
        txt = txt.replace("tio", "sio");
        txt = txt.replace("tia", "sia");
        txt = txt.replace("d", "t");
        txt = txt.replace("ph", "fh");
        txt = txt.replace("b", "p");
        txt = txt.replace("sh", "s2");
        txt = txt.replace("z", "s");
        txt = txt.replaceAll("^[aeiou]", "A");
        txt = txt.replaceAll("[aeiou]", "3");
        txt = txt.replace("j", "y");
        txt = txt.replaceAll("^y3", "Y3");
        txt = txt.replaceAll("^y", "A");
        txt = txt.replace("y", "3");
        txt = txt.replace("3gh3", "3kh3");
        txt = txt.replace("gh", "22");
        txt = txt.replace("g", "k");
        txt = txt.replaceAll("s+", "S");
        txt = txt.replaceAll("t+", "T");
        txt = txt.replaceAll("p+", "P");
        txt = txt.replaceAll("k+", "K");
        txt = txt.replaceAll("f+", "F");
        txt = txt.replaceAll("m+", "M");
        txt = txt.replaceAll("n+", "N");
        txt = txt.replace("w3", "W3");
        txt = txt.replace("wh3", "Wh3");
        txt = txt.replaceAll("w$", "3");
        txt = txt.replace("w", "2");
        txt = txt.replaceAll("^h", "A");
        txt = txt.replace("h", "2");
        txt = txt.replace("r3", "R3");
        txt = txt.replaceAll("r$", "3");
        txt = txt.replace("r", "2");
        txt = txt.replace("l3", "L3");
        txt = txt.replaceAll("l$", "3");
        txt = txt.replace("l", "2");
        txt = txt.replace("2", "");
        txt = txt.replaceAll("3$", "A");
        txt = txt.replace("3", "");
        txt = txt + TEN_1;
        return txt.substring(0, TEN_1.length());
    }

    public boolean isEncodeEqual(String str1, String str2) {
        return encode(str1).equals(encode(str2));
    }
}
