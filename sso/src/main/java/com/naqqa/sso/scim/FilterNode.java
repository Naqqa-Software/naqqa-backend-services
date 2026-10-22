package com.naqqa.sso.scim;

import java.util.List;

public sealed interface FilterNode {
    enum Op {
        EQ, NE, CO, SW, EW, GT, GE, LT, LE;

        static Op parse(String word) {
            return switch (word.toLowerCase(java.util.Locale.ROOT)) {
                case "eq" -> EQ;
                case "ne" -> NE;
                case "co" -> CO;
                case "sw" -> SW;
                case "ew" -> EW;
                case "gt" -> GT;
                case "ge" -> GE;
                case "lt" -> LT;
                case "le" -> LE;
                default -> null;
            };
        }
    }

    record Compare(AttrPath path, Op op, Object literal) implements FilterNode {
    }

    record Present(AttrPath path) implements FilterNode {
    }

    record And(List<FilterNode> items) implements FilterNode {
    }

    record Or(List<FilterNode> items) implements FilterNode {
    }

    record Not(FilterNode inner) implements FilterNode {
    }

    record ValuePath(AttrPath path, FilterNode inner) implements FilterNode {
    }
}
