package com.naqqa.sso.scim;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class FilterEvaluator {
    private FilterEvaluator() {
    }

    public static boolean matches(FilterNode node, Map<String, Object> view) {
        return eval(node, view);
    }

    private static boolean eval(FilterNode node, Map<String, Object> scope) {
        if (node instanceof FilterNode.And a) {
            for (FilterNode n : a.items()) {
                if (!eval(n, scope)) {
                    return false;
                }
            }
            return true;
        }
        if (node instanceof FilterNode.Or o) {
            for (FilterNode n : o.items()) {
                if (eval(n, scope)) {
                    return true;
                }
            }
            return false;
        }
        if (node instanceof FilterNode.Not n) {
            return !eval(n.inner(), scope);
        }
        if (node instanceof FilterNode.Present p) {
            for (Object v : values(p.path(), scope)) {
                if (nonEmpty(v)) {
                    return true;
                }
            }
            return false;
        }
        if (node instanceof FilterNode.ValuePath vp) {
            Object v = container(vp.path(), scope).get(vp.path().attr().name());
            if (v instanceof List<?> l) {
                for (Object e : l) {
                    if (e instanceof Map<?, ?> m && eval(vp.inner(), ScimData.map(m))) {
                        return true;
                    }
                }
            } else if (v instanceof Map<?, ?> m) {
                return eval(vp.inner(), ScimData.map(m));
            }
            return false;
        }
        FilterNode.Compare c = (FilterNode.Compare) node;
        List<Object> vals = values(c.path(), scope);
        if (c.literal() == FilterParser.NullLiteral.INSTANCE) {
            boolean any = false;
            for (Object v : vals) {
                if (v != null) {
                    any = true;
                }
            }
            return c.op() == FilterNode.Op.EQ ? !any : any;
        }
        AttrDef leaf = c.path().leaf();
        if (c.op() == FilterNode.Op.NE) {
            for (Object v : vals) {
                if (compare(leaf, FilterNode.Op.EQ, v, c.literal())) {
                    return false;
                }
            }
            return true;
        }
        for (Object v : vals) {
            if (compare(leaf, c.op(), v, c.literal())) {
                return true;
            }
        }
        return false;
    }

    private static boolean nonEmpty(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof String s) {
            return !s.isEmpty();
        }
        if (v instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        if (v instanceof List<?> l) {
            return !l.isEmpty();
        }
        return true;
    }

    private static Map<String, Object> container(AttrPath p, Map<String, Object> scope) {
        if (p.relative() || p.urn() == null) {
            return scope;
        }
        Object ext = scope.get(p.urn());
        return ext instanceof Map<?, ?> m ? ScimData.map(m) : Map.of();
    }

    static List<Object> values(AttrPath p, Map<String, Object> scope) {
        Object v = container(p, scope).get(p.attr().name());
        List<Object> out = new ArrayList<>();
        if (p.sub() != null) {
            collectSub(v, p.sub().name(), out);
        } else {
            flatten(v, out);
        }
        return out;
    }

    private static void collectSub(Object v, String sub, List<Object> out) {
        if (v instanceof Map<?, ?> m) {
            Object s = m.get(sub);
            if (s != null) {
                out.add(s);
            }
        } else if (v instanceof List<?> l) {
            for (Object e : l) {
                collectSub(e, sub, out);
            }
        }
    }

    private static void flatten(Object v, List<Object> out) {
        if (v == null) {
            return;
        }
        if (v instanceof List<?> l) {
            out.addAll(l);
        } else {
            out.add(v);
        }
    }

    private static boolean compare(AttrDef leaf, FilterNode.Op op, Object stored, Object literal) {
        if (stored instanceof Boolean b) {
            return literal instanceof Boolean l && b.equals(l) && op == FilterNode.Op.EQ;
        }
        if (!(stored instanceof String s) || !(literal instanceof String l)) {
            return false;
        }
        if (leaf.type() == AttrDef.Type.DATETIME) {
            try {
                int cmp = Instant.parse(s).compareTo(Instant.parse(l));
                return order(op, cmp);
            } catch (RuntimeException e) {
                return false;
            }
        }
        String a = leaf.caseExact() ? s : s.toLowerCase(Locale.ROOT);
        String b = leaf.caseExact() ? l : l.toLowerCase(Locale.ROOT);
        return switch (op) {
            case EQ -> a.equals(b);
            case CO -> a.contains(b);
            case SW -> a.startsWith(b);
            case EW -> a.endsWith(b);
            default -> order(op, a.compareTo(b));
        };
    }

    private static boolean order(FilterNode.Op op, int cmp) {
        return switch (op) {
            case EQ -> cmp == 0;
            case GT -> cmp > 0;
            case GE -> cmp >= 0;
            case LT -> cmp < 0;
            case LE -> cmp <= 0;
            default -> false;
        };
    }
}
