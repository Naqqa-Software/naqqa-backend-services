package com.naqqa.sso.scim;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record ResourceDef(
        String name,
        String endpoint,
        String description,
        String coreSchema,
        List<AttrDef> core,
        Map<String, List<AttrDef>> extensions) {

    public ResourceDef {
        core = List.copyOf(core);
        Map<String, List<AttrDef>> copy = new LinkedHashMap<>();
        extensions.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        extensions = java.util.Collections.unmodifiableMap(copy);
    }

    public AttrDef coreAttr(String attrName) {
        return find(core, attrName);
    }

    public static AttrDef find(List<AttrDef> defs, String attrName) {
        if (attrName == null) {
            return null;
        }
        String lower = attrName.toLowerCase(Locale.ROOT);
        for (AttrDef d : defs) {
            if (d.name().toLowerCase(Locale.ROOT).equals(lower)) {
                return d;
            }
        }
        return null;
    }

    public String extensionUrn(String raw) {
        if (raw == null) {
            return null;
        }
        for (String urn : extensions.keySet()) {
            if (urn.equalsIgnoreCase(raw)) {
                return urn;
            }
        }
        return null;
    }

    public AttrPath resolve(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 512) {
            return null;
        }
        String urn = null;
        String rest = raw;
        List<AttrDef> scope = core;
        if (raw.regionMatches(true, 0, "urn:", 0, 4)) {
            String lower = raw.toLowerCase(Locale.ROOT);
            boolean matched = false;
            if (lower.startsWith(coreSchema.toLowerCase(Locale.ROOT) + ":")) {
                rest = raw.substring(coreSchema.length() + 1);
                matched = true;
            } else {
                for (Map.Entry<String, List<AttrDef>> e : extensions.entrySet()) {
                    if (lower.startsWith(e.getKey().toLowerCase(Locale.ROOT) + ":")) {
                        urn = e.getKey();
                        rest = raw.substring(e.getKey().length() + 1);
                        scope = e.getValue();
                        matched = true;
                        break;
                    }
                }
            }
            if (!matched) {
                return null;
            }
        }
        int dot = rest.indexOf('.');
        String attrName = dot < 0 ? rest : rest.substring(0, dot);
        AttrDef attr = find(scope, attrName);
        if (attr == null) {
            return null;
        }
        AttrDef sub = null;
        if (dot >= 0) {
            String subName = rest.substring(dot + 1);
            if (attr.type() != AttrDef.Type.COMPLEX || subName.indexOf('.') >= 0) {
                return null;
            }
            sub = attr.sub(subName);
            if (sub == null) {
                return null;
            }
        }
        return new AttrPath(urn, attr, sub, false);
    }
}
