package com.naqqa.chatbot.ai;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PiiMasker {

    public static final String EMAIL = "[email]";
    public static final String PHONE = "[telefon]";
    public static final String CARD = "[card]";
    public static final String IBAN = "[iban]";
    public static final String IDNP = "[idnp]";
    public static final String SECRET = "[parola]";

    private static final Pattern PASSWORD = Pattern.compile(
            "(?iU)\\b(parola\\s+mea|parol[aă]|parolei|пароль|password|passwd|pwd|pin|пин)\\b(\\s*[:=]\\s*|\\s+(?:este|e|is|это|-|—)\\s+|\\s+)([^\\s,.;!?]+)");
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "(?iu)[\\p{L}0-9._%+\\-]+@[\\p{L}0-9.\\-]+\\.[\\p{L}]{2,}");
    private static final Pattern IBAN_PATTERN = Pattern.compile(
            "(?i)\\b[A-Z]{2}\\d{2}(?:[ ]?[A-Z0-9]{4}){3,7}(?:[ ]?[A-Z0-9]{1,4})?\\b");
    private static final Pattern DIGIT_RUN = Pattern.compile("(?<![\\d])\\d(?:[ \\-]?\\d){11,18}(?![\\d])");
    private static final Pattern PHONE_INTL = Pattern.compile(
            "(?<![\\w+])\\+\\s?\\d{1,3}(?:[\\s\\-.()]{0,2}\\d){6,12}(?!\\d)");
    private static final Pattern PHONE_MD = Pattern.compile(
            "(?<![\\d])(?:00\\s?373|373)?[\\s\\-]?\\(?0?[2-9]\\d\\)?(?:[\\s\\-.]?\\d){6}(?![\\d])");

    private PiiMasker() {
    }

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = maskPasswords(text);
        out = EMAIL_PATTERN.matcher(out).replaceAll(EMAIL);
        out = IBAN_PATTERN.matcher(out).replaceAll(m -> looksLikeIban(m.group()) ? IBAN : Matcher.quoteReplacement(m.group()));
        out = maskDigitRuns(out);
        out = PHONE_INTL.matcher(out).replaceAll(PHONE);
        out = maskMoldovanPhones(out);
        return out;
    }

    public static boolean containsPii(String text) {
        return text != null && !mask(text).equals(text);
    }

    private static String maskPasswords(String text) {
        Matcher m = PASSWORD.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String sep = m.group(2).trim();
            String value = m.group(3);
            boolean explicit = !sep.isEmpty();
            boolean secretLike = value.matches(".*[\\d\\p{Punct}].*") || (value.length() >= 6 && !value.equals(value.toLowerCase()));
            String replacement = explicit || secretLike ? m.group(1) + m.group(2) + SECRET : m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean looksLikeIban(String value) {
        String compact = value.replace(" ", "");
        int digits = 0;
        for (int i = 0; i < compact.length(); i++) {
            if (Character.isDigit(compact.charAt(i))) {
                digits++;
            }
        }
        return compact.length() >= 15 && compact.length() <= 34 && digits >= 6;
    }

    private static String maskDigitRuns(String text) {
        Matcher m = DIGIT_RUN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String raw = m.group();
            String digits = raw.replaceAll("[ \\-]", "");
            String replacement = raw;
            if (digits.length() == 13 && raw.equals(digits) && (digits.charAt(0) == '0' || digits.charAt(0) == '1' || digits.charAt(0) == '2')) {
                replacement = IDNP;
            } else if (digits.length() >= 13 && digits.length() <= 19 && luhn(digits)) {
                replacement = CARD;
            } else if (digits.length() == 13) {
                replacement = IDNP;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String maskMoldovanPhones(String text) {
        Matcher m = PHONE_MD.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String raw = m.group();
            String digits = raw.replaceAll("\\D", "");
            boolean separated = raw.trim().matches(".*\\d[\\s\\-.]\\d.*");
            boolean phone = (digits.length() == 9 && digits.startsWith("0"))
                    || (digits.length() == 11 && digits.startsWith("373"))
                    || (digits.length() == 13 && digits.startsWith("00373"))
                    || (digits.length() == 8 && separated && (digits.startsWith("6") || digits.startsWith("7")));
            String lead = raw.startsWith(" ") || raw.startsWith("-") ? raw.substring(0, 1) : "";
            m.appendReplacement(sb, Matcher.quoteReplacement(phone ? lead + PHONE : raw));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static boolean luhn(String digits) {
        int sum = 0;
        boolean alt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (n < 0 || n > 9) {
                return false;
            }
            if (alt) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            alt = !alt;
        }
        return sum % 10 == 0;
    }
}
