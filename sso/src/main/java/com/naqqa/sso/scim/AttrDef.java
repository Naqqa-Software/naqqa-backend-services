package com.naqqa.sso.scim;

import java.util.List;
import java.util.Locale;

public record AttrDef(
        String name,
        Type type,
        boolean multiValued,
        Mutability mutability,
        Returned returned,
        boolean required,
        boolean caseExact,
        Uniqueness uniqueness,
        String description,
        List<AttrDef> subs) {

    public enum Type {
        STRING("string"),
        BOOLEAN("boolean"),
        COMPLEX("complex"),
        DATETIME("dateTime"),
        REFERENCE("reference");

        private final String json;

        Type(String json) {
            this.json = json;
        }

        public String json() {
            return json;
        }
    }

    public enum Mutability {
        READ_ONLY("readOnly"),
        READ_WRITE("readWrite"),
        IMMUTABLE("immutable"),
        WRITE_ONLY("writeOnly");

        private final String json;

        Mutability(String json) {
            this.json = json;
        }

        public String json() {
            return json;
        }
    }

    public enum Returned {
        DEFAULT("default"),
        NEVER("never"),
        ALWAYS("always");

        private final String json;

        Returned(String json) {
            this.json = json;
        }

        public String json() {
            return json;
        }
    }

    public enum Uniqueness {
        NONE("none"),
        SERVER("server");

        private final String json;

        Uniqueness(String json) {
            this.json = json;
        }

        public String json() {
            return json;
        }
    }

    public AttrDef {
        subs = subs == null ? List.of() : List.copyOf(subs);
    }

    public static AttrDef of(String name, Type type) {
        return new AttrDef(name, type, false, Mutability.READ_WRITE, Returned.DEFAULT, false, false,
                Uniqueness.NONE, name, List.of());
    }

    public static AttrDef string(String name) {
        return of(name, Type.STRING);
    }

    public static AttrDef bool(String name) {
        return of(name, Type.BOOLEAN);
    }

    public static AttrDef complex(String name, AttrDef... subs) {
        return new AttrDef(name, Type.COMPLEX, false, Mutability.READ_WRITE, Returned.DEFAULT, false, false,
                Uniqueness.NONE, name, List.of(subs));
    }

    public AttrDef multi() {
        return new AttrDef(name, type, true, mutability, returned, required, caseExact, uniqueness, description, subs);
    }

    public AttrDef requiredAttr() {
        return new AttrDef(name, type, multiValued, mutability, returned, true, caseExact, uniqueness, description, subs);
    }

    public AttrDef exact() {
        return new AttrDef(name, type, multiValued, mutability, returned, required, true, uniqueness, description, subs);
    }

    public AttrDef readOnly() {
        return new AttrDef(name, type, multiValued, Mutability.READ_ONLY, returned, required, caseExact, uniqueness,
                description, subs);
    }

    public AttrDef writeOnlyNever() {
        return new AttrDef(name, type, multiValued, Mutability.WRITE_ONLY, Returned.NEVER, required, caseExact,
                uniqueness, description, subs);
    }

    public AttrDef unique() {
        return new AttrDef(name, type, multiValued, mutability, returned, required, caseExact, Uniqueness.SERVER,
                description, subs);
    }

    public AttrDef always() {
        return new AttrDef(name, type, multiValued, mutability, Returned.ALWAYS, required, caseExact, uniqueness,
                description, subs);
    }

    public AttrDef describe(String text) {
        return new AttrDef(name, type, multiValued, mutability, returned, required, caseExact, uniqueness, text, subs);
    }

    public AttrDef sub(String subName) {
        if (subName == null) {
            return null;
        }
        String lower = subName.toLowerCase(Locale.ROOT);
        for (AttrDef s : subs) {
            if (s.name.toLowerCase(Locale.ROOT).equals(lower)) {
                return s;
            }
        }
        return null;
    }
}
