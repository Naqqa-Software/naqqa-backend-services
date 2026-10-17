package com.naqqa.auth.sso.scim;

import com.naqqa.sso.scim.AttrDef;
import com.naqqa.sso.scim.AttrPath;
import com.naqqa.sso.scim.FilterNode;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class ScimQueryPlanner {

    public record Plan(Criteria criteria, boolean exact) {
    }

    private ScimQueryPlanner() {
    }

    public static Plan plan(FilterNode node) {
        if (node == null) {
            return new Plan(null, true);
        }
        Criteria exact = translate(node, false);
        if (exact != null) {
            return new Plan(exact, true);
        }
        if (node instanceof FilterNode.And and) {
            List<Criteria> parts = new ArrayList<>();
            for (FilterNode item : and.items()) {
                Criteria c = translate(item, false);
                if (c != null) {
                    parts.add(c);
                }
            }
            if (parts.isEmpty()) {
                return new Plan(null, false);
            }
            return new Plan(parts.size() == 1 ? parts.get(0) : new Criteria().andOperator(parts.toArray(new Criteria[0])), false);
        }
        return new Plan(null, false);
    }

    static Criteria translate(FilterNode node, boolean relative) {
        if (node instanceof FilterNode.And a) {
            List<Criteria> parts = new ArrayList<>();
            for (FilterNode item : a.items()) {
                Criteria c = translate(item, relative);
                if (c == null) {
                    return null;
                }
                parts.add(c);
            }
            return new Criteria().andOperator(parts.toArray(new Criteria[0]));
        }
        if (node instanceof FilterNode.Or o) {
            List<Criteria> parts = new ArrayList<>();
            for (FilterNode item : o.items()) {
                Criteria c = translate(item, relative);
                if (c == null) {
                    return null;
                }
                parts.add(c);
            }
            return new Criteria().orOperator(parts.toArray(new Criteria[0]));
        }
        if (node instanceof FilterNode.Not n) {
            if (relative) {
                return null;
            }
            Criteria inner = translate(n.inner(), false);
            return inner == null ? null : new Criteria().norOperator(inner);
        }
        if (node instanceof FilterNode.Present p) {
            return relative ? presentRelative(p.path()) : present(p.path());
        }
        if (node instanceof FilterNode.ValuePath vp) {
            return valuePath(vp);
        }
        FilterNode.Compare c = (FilterNode.Compare) node;
        return relative ? compareRelative(c) : compare(c);
    }

    static String base(AttrPath p) {
        return p.urn() == null ? "" : ScimDocuments.escape(p.urn()) + ".";
    }

    static boolean core(AttrPath p) {
        return p.urn() == null;
    }

    static Criteria present(AttrPath p) {
        AttrDef attr = p.attr();
        if (core(p) && (attr.name().equals("id") || attr.name().equals("meta") || attr.name().equals("groups"))) {
            return null;
        }
        if (derived(p)) {
            return null;
        }
        String field = "attributes." + base(p) + ScimDocuments.escape(attr.name());
        if (p.sub() == null) {
            if (attr.type() == AttrDef.Type.COMPLEX || attr.multiValued()) {
                return null;
            }
            return Criteria.where(field).exists(true).nin(null, "");
        }
        if (attr.multiValued()) {
            return Criteria.where(field).elemMatch(Criteria.where(ScimDocuments.escape(p.sub().name())).exists(true).nin(null, ""));
        }
        if (p.sub().multiValued()) {
            return null;
        }
        return Criteria.where(field + "." + ScimDocuments.escape(p.sub().name())).exists(true).nin(null, "");
    }

    static boolean derived(AttrPath p) {
        return core(p) && p.attr().name().equals("members") && p.sub() != null
                && !p.sub().name().equals("value") && !p.sub().name().equals("type");
    }

    static Criteria presentRelative(AttrPath p) {
        if (p.attr().multiValued() || p.attr().type() == AttrDef.Type.COMPLEX) {
            return null;
        }
        return Criteria.where(ScimDocuments.escape(p.attr().name())).exists(true).nin(null, "");
    }

    static Pattern regex(FilterNode.Op op, String literal, boolean insensitive) {
        String q = Pattern.quote(literal);
        String expr = switch (op) {
            case EQ -> "^" + q + "$";
            case SW -> "^" + q;
            case EW -> q + "$";
            case CO -> q;
            default -> null;
        };
        if (expr == null) {
            return null;
        }
        return insensitive ? Pattern.compile(expr, Pattern.CASE_INSENSITIVE) : Pattern.compile(expr);
    }

    static Criteria stringOp(String field, FilterNode.Op op, String literal) {
        return switch (op) {
            case EQ -> Criteria.where(field).is(literal);
            case NE -> Criteria.where(field).ne(literal);
            case SW, EW, CO -> Criteria.where(field).regex(regex(op, literal, false));
            case GT -> Criteria.where(field).gt(literal);
            case GE -> Criteria.where(field).gte(literal);
            case LT -> Criteria.where(field).lt(literal);
            case LE -> Criteria.where(field).lte(literal);
        };
    }

    static Criteria compare(FilterNode.Compare c) {
        AttrPath p = c.path();
        AttrDef attr = p.attr();
        AttrDef leaf = p.leaf();
        Object literal = c.literal();
        if (core(p) && attr.name().equals("meta")) {
            if (p.sub() == null || !(literal instanceof String s)) {
                return null;
            }
            String field = switch (p.sub().name()) {
                case "created" -> "created";
                case "lastModified" -> "lastModified";
                default -> null;
            };
            if (field == null) {
                return null;
            }
            Date d;
            try {
                d = Date.from(Instant.parse(s));
            } catch (RuntimeException e) {
                return null;
            }
            return switch (c.op()) {
                case EQ -> Criteria.where(field).is(d);
                case NE -> Criteria.where(field).ne(d);
                case GT -> Criteria.where(field).gt(d);
                case GE -> Criteria.where(field).gte(d);
                case LT -> Criteria.where(field).lt(d);
                case LE -> Criteria.where(field).lte(d);
                default -> null;
            };
        }
        if (core(p) && attr.name().equals("id")) {
            return literal instanceof String s ? stringOp("rid", c.op(), s) : null;
        }
        if (core(p) && attr.name().equals("groups")) {
            return null;
        }
        if (derived(p)) {
            return null;
        }
        if (leaf.type() == AttrDef.Type.BOOLEAN) {
            if (!(literal instanceof Boolean b) || attr.multiValued() || (p.sub() != null && p.sub().multiValued())) {
                return null;
            }
            String field = "attributes." + base(p) + ScimDocuments.escape(attr.name())
                    + (p.sub() == null ? "" : "." + ScimDocuments.escape(p.sub().name()));
            return switch (c.op()) {
                case EQ -> Criteria.where(field).is(b);
                case NE -> Criteria.where(field).ne(b);
                default -> null;
            };
        }
        if (leaf.type() != AttrDef.Type.STRING && leaf.type() != AttrDef.Type.REFERENCE) {
            return null;
        }
        if (!(literal instanceof String s)) {
            return null;
        }
        boolean insensitive = !leaf.caseExact();
        String path = base(p) + ScimDocuments.escape(attr.name()) + (p.sub() == null ? "" : "." + ScimDocuments.escape(p.sub().name()));
        boolean single = !attr.multiValued() && (p.sub() == null || !p.sub().multiValued())
                && (p.sub() != null || attr.type() != AttrDef.Type.COMPLEX);
        if (single) {
            if (insensitive) {
                return stringOp("lc." + path, c.op(), s.toLowerCase(Locale.ROOT));
            }
            return stringOp("attributes." + path, c.op(), s);
        }
        if (!attr.multiValued() || p.sub() == null) {
            return null;
        }
        Pattern pattern = regex(c.op(), s, insensitive);
        if (pattern == null) {
            return null;
        }
        if (!insensitive && c.op() == FilterNode.Op.EQ) {
            return Criteria.where("attributes." + path).is(s);
        }
        return Criteria.where("attributes." + path).regex(pattern);
    }

    static Criteria compareRelative(FilterNode.Compare c) {
        AttrPath p = c.path();
        AttrDef leaf = p.leaf();
        if (p.attr().multiValued() || p.sub() != null) {
            return null;
        }
        String field = ScimDocuments.escape(p.attr().name());
        Object literal = c.literal();
        if (leaf.type() == AttrDef.Type.BOOLEAN) {
            if (literal instanceof Boolean b && c.op() == FilterNode.Op.EQ) {
                return Criteria.where(field).is(b);
            }
            return null;
        }
        if ((leaf.type() != AttrDef.Type.STRING && leaf.type() != AttrDef.Type.REFERENCE) || !(literal instanceof String s)) {
            return null;
        }
        Pattern pattern = regex(c.op(), s, !leaf.caseExact());
        if (pattern == null) {
            return null;
        }
        if (leaf.caseExact() && c.op() == FilterNode.Op.EQ) {
            return Criteria.where(field).is(s);
        }
        return Criteria.where(field).regex(pattern);
    }

    static Criteria valuePath(FilterNode.ValuePath vp) {
        AttrPath p = vp.path();
        if (!p.attr().multiValued() || p.attr().type() != AttrDef.Type.COMPLEX
                || (core(p) && (p.attr().name().equals("groups") || p.attr().name().equals("members")))) {
            return null;
        }
        Criteria inner = translate(vp.inner(), true);
        if (inner == null) {
            return null;
        }
        return Criteria.where("attributes." + base(p) + ScimDocuments.escape(p.attr().name())).elemMatch(inner);
    }

    public static Sort sort(AttrPath path, boolean descending, boolean user) {
        Sort.Direction dir = descending ? Sort.Direction.DESC : Sort.Direction.ASC;
        if (path == null) {
            return Sort.by(Sort.Order.asc("created"), Sort.Order.asc("rid"));
        }
        String field = null;
        if (path.urn() == null) {
            String name = path.attr().name();
            if (name.equals("meta") && path.sub() != null) {
                if (path.sub().name().equals("created")) {
                    field = "created";
                } else if (path.sub().name().equals("lastModified")) {
                    field = "lastModified";
                }
            } else if (name.equals("id")) {
                field = "rid";
            } else if (user && name.equals("userName") && path.sub() == null) {
                field = "lc.userName";
            } else if (!user && name.equals("displayName") && path.sub() == null) {
                field = "lc.displayName";
            }
        }
        if (field == null) {
            return null;
        }
        if (field.equals("rid")) {
            return Sort.by(new Sort.Order(dir, "rid"));
        }
        return Sort.by(new Sort.Order(dir, field), Sort.Order.asc("rid"));
    }
}
