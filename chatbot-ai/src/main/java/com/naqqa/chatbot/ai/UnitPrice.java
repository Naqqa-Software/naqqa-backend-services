package com.naqqa.chatbot.ai;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UnitPrice {

    public static final String KG = "kg";
    public static final String L = "l";
    public static final String PCS = "pcs";

    public record Size(double amount, String unit) {
    }

    private static final Pattern MULTI = Pattern.compile(
            "(\\d+)\\s*(?:buc\\.?|шт\\.?)?\\s*(?:x|×|\\*)\\s*(\\d+(?:[.,]\\d+)?)\\s*(kg|кг|g|gr|г|гр|ml|мл|l|л|litr[iu]?|литр[а-я]*)(?![\\p{L}])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SINGLE = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(kg|кг|g|gr|г|гр|ml|мл|l|л|litr[iu]?|литр[а-я]*)(?![\\p{L}])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern PER_KG = Pattern.compile(
            "(?:pre[țt]\\s*/\\s*kg|цена\\s*/\\s*кг|/\\s*kg|/\\s*кг|(?:^|\\s)(?:kg|кг)\\.?\\s*$)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern PIECES = Pattern.compile("(\\d+)\\s*(?:buc|bucăți|bucati|шт)(?![\\p{L}])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private UnitPrice() {
    }

    public static Size size(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        String t = title.replace(' ', ' ');
        Matcher multi = MULTI.matcher(t);
        if (multi.find()) {
            Size one = convert(multi.group(2), multi.group(3));
            double count = parse(multi.group(1));
            if (one != null && count > 0 && count <= 100) {
                return new Size(one.amount() * count, one.unit());
            }
        }
        Size best = null;
        Matcher m = SINGLE.matcher(t);
        while (m.find()) {
            Size s = convert(m.group(1), m.group(2));
            if (s != null) {
                best = s;
                break;
            }
        }
        if (best != null) {
            return best;
        }
        if (PER_KG.matcher(t).find()) {
            return new Size(1, KG);
        }
        Matcher pcs = PIECES.matcher(t);
        if (pcs.find()) {
            double n = parse(pcs.group(1));
            if (n > 1 && n <= 500) {
                return new Size(n, PCS);
            }
        }
        return null;
    }

    public static Double perUnit(Double price, Size size) {
        if (price == null || price <= 0 || size == null || size.amount() <= 0) {
            return null;
        }
        return Math.round(price / size.amount() * 100.0) / 100.0;
    }

    private static Size convert(String number, String unit) {
        double v = parse(number);
        if (v <= 0) {
            return null;
        }
        String u = unit.toLowerCase(Locale.ROOT);
        if (u.equals("kg") || u.equals("кг")) {
            return v <= 50 ? new Size(v, KG) : null;
        }
        if (u.equals("g") || u.equals("gr") || u.equals("г") || u.equals("гр")) {
            return v >= 5 && v <= 50_000 ? new Size(v / 1000.0, KG) : null;
        }
        if (u.equals("ml") || u.equals("мл")) {
            return v >= 5 && v <= 50_000 ? new Size(v / 1000.0, L) : null;
        }
        return v <= 50 ? new Size(v, L) : null;
    }

    private static double parse(String number) {
        try {
            return Double.parseDouble(number.replace(',', '.'));
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
