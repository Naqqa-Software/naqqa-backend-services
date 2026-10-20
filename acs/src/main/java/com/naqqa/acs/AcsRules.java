package com.naqqa.acs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AcsRules {

    private final List<AcsFilter.Rule> rules;

    private AcsRules(List<AcsFilter.Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    public static AcsRules of(List<String> sensitive, List<String> signed, boolean withSignatures) {
        List<AcsFilter.Rule> out = new ArrayList<>();
        for (String spec : sensitive == null ? List.<String>of() : sensitive) {
            if (spec == null || spec.isBlank()) {
                continue;
            }
            String[] p = split(spec, 3);
            out.add(AcsFilter.Rule.sensitive(method(p[0]), p[1], p[2]));
        }
        if (withSignatures) {
            for (String spec : signed == null ? List.<String>of() : signed) {
                if (spec == null || spec.isBlank()) {
                    continue;
                }
                String[] p = split(spec, 2);
                out.add(AcsFilter.Rule.signed(method(p[0]), p[1]));
            }
        }
        return new AcsRules(out);
    }

    public List<AcsFilter.Rule> rules() {
        return rules;
    }

    public int size() {
        return rules.size();
    }

    public boolean covers(String method, String path) {
        String m = method == null ? "" : method.toUpperCase(Locale.ROOT);
        for (AcsFilter.Rule rule : rules) {
            if (rule.matches(m, path)) {
                return true;
            }
        }
        return false;
    }

    private static String method(String value) {
        return "*".equals(value) ? null : value.toUpperCase(Locale.ROOT);
    }

    private static String[] split(String spec, int parts) {
        String[] p = spec.trim().split("\\s+");
        if (p.length != parts) {
            throw new IllegalArgumentException("ACS rule \"" + spec.trim() + "\" must be \"METHOD PATH"
                    + (parts == 3 ? " ACTION\"" : "\""));
        }
        return p;
    }
}
