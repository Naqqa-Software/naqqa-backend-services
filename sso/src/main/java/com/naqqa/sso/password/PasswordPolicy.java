package com.naqqa.sso.password;

import com.naqqa.sso.SsoException;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class PasswordPolicy {
    public static final int DEFAULT_MIN_LENGTH = 12;
    public static final int DEFAULT_MAX_LENGTH = 128;
    public static final int MIN_CONTEXT_TOKEN = 4;
    public static final Set<String> DEFAULT_BLOCK_LIST = Set.of(
            "password", "passw0rd", "passwort", "parola", "parolamea", "qwerty", "qwertyuiop", "asdfghjkl",
            "zxcvbnm", "letmein", "welcome", "iloveyou", "admin", "administrator", "monkey", "dragon",
            "football", "baseball", "sunshine", "princess", "trustno1", "changeme", "default", "secret",
            "naqqa", "qazwsx", "abc123", "123456789012", "1234567890", "111111111111", "000000000000",
            "qwerty123456", "password1234", "iloveyou1234", "1q2w3e4r5t6y", "1qaz2wsx3edc", "zaq12wsx");

    public record Result(boolean accepted, List<String> violations) {
        public Result {
            violations = List.copyOf(violations);
        }
    }

    private final int minLength;
    private final int maxLength;
    private final Set<String> blockList;
    private final BreachedPasswordFilter breached;

    private PasswordPolicy(Builder b) {
        this.minLength = b.minLength;
        this.maxLength = b.maxLength;
        Set<String> s = new HashSet<>();
        for (String w : b.blockList) {
            s.add(fold(w));
        }
        this.blockList = Set.copyOf(s);
        this.breached = b.breached;
    }

    public static PasswordPolicy defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static String normalize(String password) {
        return Normalizer.normalize(password, Normalizer.Form.NFKC);
    }

    public int minLength() {
        return minLength;
    }

    public int maxLength() {
        return maxLength;
    }

    public void enforce(String password, Collection<String> context) {
        Result r = evaluate(password, context);
        if (!r.accepted()) {
            throw new SsoException(r.violations().get(0));
        }
    }

    public Result evaluate(String password, Collection<String> context) {
        List<String> v = new ArrayList<>();
        if (password == null || password.isEmpty()) {
            v.add("password.empty");
            return new Result(false, v);
        }
        String n = normalize(password);
        int length = n.codePointCount(0, n.length());
        if (length < minLength) {
            v.add("password.too_short");
        }
        if (length > maxLength) {
            v.add("password.too_long");
        }
        if (hasInvalidChars(n)) {
            v.add("password.invalid_chars");
        }
        String folded = fold(n);
        if (isBlocked(folded)) {
            v.add("password.blocked");
        }
        if (isRepetitiveOrSequential(folded)) {
            v.add("password.repetitive");
        }
        if (context != null && containsContext(folded, context)) {
            v.add("password.context");
        }
        if (breached != null && breached.mightBeBreached(password)) {
            v.add("password.breached");
        }
        return new Result(v.isEmpty(), v);
    }

    private static String fold(String s) {
        return normalize(s).toLowerCase(Locale.ROOT);
    }

    private static boolean hasInvalidChars(String s) {
        return s.codePoints().anyMatch(cp -> Character.getType(cp) == Character.CONTROL
                || Character.getType(cp) == Character.SURROGATE
                || Character.getType(cp) == Character.UNASSIGNED);
    }

    private boolean isBlocked(String folded) {
        if (blockList.contains(folded)) {
            return true;
        }
        String core = stripNonLetters(folded);
        return !core.isEmpty() && blockList.contains(core);
    }

    private static String stripNonLetters(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && !Character.isLetter(s.charAt(start))) {
            start++;
        }
        while (end > start && !Character.isLetter(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    static boolean isRepetitiveOrSequential(String s) {
        int[] cps = s.codePoints().toArray();
        if (cps.length < 2) {
            return false;
        }
        if (s.codePoints().distinct().count() <= 2) {
            return true;
        }
        boolean up = true;
        boolean down = true;
        for (int i = 1; i < cps.length; i++) {
            if (cps[i] != cps[i - 1] + 1) {
                up = false;
            }
            if (cps[i] != cps[i - 1] - 1) {
                down = false;
            }
        }
        return up || down;
    }

    private static boolean containsContext(String folded, Collection<String> context) {
        for (String c : context) {
            if (c == null || c.isBlank()) {
                continue;
            }
            String fc = fold(c.strip());
            if (folded.equals(fc)) {
                return true;
            }
            for (String token : fc.split("[^\\p{L}\\p{N}]+")) {
                if (token.length() >= MIN_CONTEXT_TOKEN && folded.contains(token)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static final class Builder {
        private int minLength = DEFAULT_MIN_LENGTH;
        private int maxLength = DEFAULT_MAX_LENGTH;
        private final Set<String> blockList = new HashSet<>(DEFAULT_BLOCK_LIST);
        private BreachedPasswordFilter breached;

        private Builder() {
        }

        public Builder minLength(int value) {
            if (value < 8 || value > 128) {
                throw new SsoException("password.policy_invalid");
            }
            this.minLength = value;
            return this;
        }

        public Builder maxLength(int value) {
            if (value < 64 || value > PasswordHasher.MAX_PASSWORD_CHARS) {
                throw new SsoException("password.policy_invalid");
            }
            this.maxLength = value;
            return this;
        }

        public Builder blockWords(Collection<String> words) {
            for (String w : words) {
                if (w != null && !w.isBlank()) {
                    blockList.add(w.strip());
                }
            }
            return this;
        }

        public Builder breachedFilter(BreachedPasswordFilter filter) {
            this.breached = filter;
            return this;
        }

        public PasswordPolicy build() {
            if (minLength > maxLength) {
                throw new SsoException("password.policy_invalid");
            }
            return new PasswordPolicy(this);
        }
    }
}
