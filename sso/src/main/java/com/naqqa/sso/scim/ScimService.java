package com.naqqa.sso.scim;

import com.naqqa.sso.SsoException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class ScimService {
    private final ScimUserStore users;
    private final ScimGroupStore groups;
    private final ScimHooks hooks;
    private final ScimConfig config;
    private final Clock clock;

    public ScimService(ScimUserStore users, ScimGroupStore groups, ScimHooks hooks, ScimConfig config, Clock clock) {
        this.users = Objects.requireNonNull(users);
        this.groups = Objects.requireNonNull(groups);
        this.hooks = hooks == null ? ScimHooks.NONE : hooks;
        this.config = Objects.requireNonNull(config);
        this.clock = Objects.requireNonNull(clock);
    }

    public ScimResponse createUser(String tenantId, Map<String, Object> body) {
        return guard(() -> create(tenantId, true, body));
    }

    public ScimResponse getUser(String tenantId, String id, String ifNoneMatch) {
        return guard(() -> get(tenantId, true, id, ifNoneMatch));
    }

    public ScimResponse listUsers(String tenantId, ListQuery query) {
        return guard(() -> list(tenantId, true, query));
    }

    public ScimResponse searchUsers(String tenantId, Map<String, Object> body) {
        return guard(() -> list(tenantId, true, ListQuery.fromBody(body)));
    }

    public ScimResponse replaceUser(String tenantId, String id, Map<String, Object> body, String ifMatch) {
        return guard(() -> replace(tenantId, true, id, body, ifMatch));
    }

    public ScimResponse patchUser(String tenantId, String id, Map<String, Object> body, String ifMatch) {
        return guard(() -> patch(tenantId, true, id, body, ifMatch));
    }

    public ScimResponse deleteUser(String tenantId, String id, String ifMatch) {
        return guard(() -> delete(tenantId, true, id, ifMatch));
    }

    public ScimResponse createGroup(String tenantId, Map<String, Object> body) {
        return guard(() -> create(tenantId, false, body));
    }

    public ScimResponse getGroup(String tenantId, String id, String ifNoneMatch) {
        return guard(() -> get(tenantId, false, id, ifNoneMatch));
    }

    public ScimResponse listGroups(String tenantId, ListQuery query) {
        return guard(() -> list(tenantId, false, query));
    }

    public ScimResponse searchGroups(String tenantId, Map<String, Object> body) {
        return guard(() -> list(tenantId, false, ListQuery.fromBody(body)));
    }

    public ScimResponse replaceGroup(String tenantId, String id, Map<String, Object> body, String ifMatch) {
        return guard(() -> replace(tenantId, false, id, body, ifMatch));
    }

    public ScimResponse patchGroup(String tenantId, String id, Map<String, Object> body, String ifMatch) {
        return guard(() -> patch(tenantId, false, id, body, ifMatch));
    }

    public ScimResponse deleteGroup(String tenantId, String id, String ifMatch) {
        return guard(() -> delete(tenantId, false, id, ifMatch));
    }

    public ScimResponse serviceProviderConfig(String tenantId) {
        return guard(() -> {
            tenant(tenantId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("schemas", List.of(ScimSchemas.SERVICE_PROVIDER_CONFIG));
            m.put("documentationUri", config.baseUrl() + "/ServiceProviderConfig");
            m.put("patch", Map.of("supported", true));
            Map<String, Object> bulk = new LinkedHashMap<>();
            bulk.put("supported", false);
            bulk.put("maxOperations", 0);
            bulk.put("maxPayloadSize", 0);
            m.put("bulk", bulk);
            Map<String, Object> filter = new LinkedHashMap<>();
            filter.put("supported", true);
            filter.put("maxResults", config.maxCount());
            m.put("filter", filter);
            m.put("changePassword", Map.of("supported", false));
            m.put("sort", Map.of("supported", true));
            m.put("etag", Map.of("supported", true));
            Map<String, Object> scheme = new LinkedHashMap<>();
            scheme.put("type", "oauthbearertoken");
            scheme.put("name", "OAuth Bearer Token");
            scheme.put("description", "Authentication scheme using the OAuth Bearer Token Standard");
            scheme.put("primary", true);
            m.put("authenticationSchemes", List.of(scheme));
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("resourceType", "ServiceProviderConfig");
            meta.put("location", config.baseUrl() + "/ServiceProviderConfig");
            m.put("meta", meta);
            return new ScimResponse(200, m, Map.of());
        });
    }

    public ScimResponse resourceTypes(String tenantId) {
        return guard(() -> {
            tenant(tenantId);
            List<Object> all = new ArrayList<>();
            all.add(resourceType(ScimSchemas.USER_DEF));
            all.add(resourceType(ScimSchemas.GROUP_DEF));
            return new ScimResponse(200, listBody(all, all.size(), 1, all.size()), Map.of());
        });
    }

    public ScimResponse resourceType(String tenantId, String id) {
        return guard(() -> {
            tenant(tenantId);
            if ("User".equals(id)) {
                return new ScimResponse(200, resourceType(ScimSchemas.USER_DEF), Map.of());
            }
            if ("Group".equals(id)) {
                return new ScimResponse(200, resourceType(ScimSchemas.GROUP_DEF), Map.of());
            }
            throw new SsoException("scim.not_found");
        });
    }

    public ScimResponse schemas(String tenantId) {
        return guard(() -> {
            tenant(tenantId);
            List<Object> all = new ArrayList<>();
            for (String id : ScimSchemas.ids()) {
                all.add(ScimSchemas.describe(id, config.baseUrl()));
            }
            return new ScimResponse(200, listBody(all, all.size(), 1, all.size()), Map.of());
        });
    }

    public ScimResponse schema(String tenantId, String id) {
        return guard(() -> {
            tenant(tenantId);
            Map<String, Object> m = id == null ? null : ScimSchemas.describe(id, config.baseUrl());
            if (m == null) {
                throw new SsoException("scim.not_found");
            }
            return new ScimResponse(200, m, Map.of());
        });
    }

    private Map<String, Object> resourceType(ResourceDef d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemas", List.of(ScimSchemas.RESOURCE_TYPE));
        m.put("id", d.name());
        m.put("name", d.name());
        m.put("endpoint", d.endpoint());
        m.put("description", d.description());
        m.put("schema", d.coreSchema());
        List<Object> ext = new ArrayList<>();
        for (String urn : d.extensions().keySet()) {
            ext.add(Map.of("schema", urn, "required", false));
        }
        m.put("schemaExtensions", ext);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("resourceType", "ResourceType");
        meta.put("location", config.baseUrl() + "/ResourceTypes/" + d.name());
        m.put("meta", meta);
        return m;
    }

    private interface Action {
        ScimResponse run();
    }

    private ScimResponse guard(Action a) {
        try {
            return a.run();
        } catch (SsoException e) {
            return ScimErrors.of(e);
        } catch (RuntimeException e) {
            return ScimErrors.of(new IllegalStateException());
        }
    }

    private static void tenant(String tenantId) {
        if (tenantId == null || tenantId.isEmpty() || tenantId.length() > 128) {
            throw new SsoException("scim.invalid_value");
        }
        for (int i = 0; i < tenantId.length(); i++) {
            char c = tenantId.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == ':';
            if (!ok) {
                throw new SsoException("scim.invalid_value");
            }
        }
    }

    private ResourceDef def(boolean user) {
        return user ? ScimSchemas.USER_DEF : ScimSchemas.GROUP_DEF;
    }

    private ScimResourceStore store(boolean user) {
        return user ? users : groups;
    }

    private static String etag(long version) {
        return "W/\"" + version + "\"";
    }

    private static boolean etagMatches(String header, long version) {
        String current = etag(version);
        for (String part : header.split(",", -1)) {
            String p = part.trim();
            if (p.equals("*")) {
                return true;
            }
            if (p.startsWith("W/")) {
                p = p.substring(2);
            }
            if (("W/" + p).equals(current)) {
                return true;
            }
        }
        return false;
    }

    private void precondition(String ifMatch, ScimResource current) {
        if (ifMatch == null || ifMatch.isBlank()) {
            if (config.requireIfMatch()) {
                throw new SsoException("scim.precondition_required");
            }
            return;
        }
        if (ifMatch.length() > 1024 || !etagMatches(ifMatch, current.version())) {
            throw new SsoException("scim.version_conflict");
        }
    }

    private void checkSchemas(Map<String, Object> body, String required) {
        if (body == null) {
            throw new SsoException("scim.invalid_syntax");
        }
        Object s = body.get("schemas");
        if (!(s instanceof List<?> l)) {
            throw new SsoException("scim.invalid_syntax");
        }
        for (Object o : l) {
            if (o instanceof String str && str.equalsIgnoreCase(required)) {
                return;
            }
        }
        throw new SsoException("scim.invalid_syntax");
    }

    private ScimResource load(String tenantId, boolean user, String id) {
        if (id == null || id.isEmpty() || id.length() > 128) {
            throw new SsoException("scim.not_found");
        }
        return store(user).find(tenantId, id).orElseThrow(() -> new SsoException("scim.not_found"));
    }

    private static boolean active(Map<String, Object> attrs) {
        return !Boolean.FALSE.equals(attrs.get("active"));
    }

    private ScimResponse single(int status, ScimResource r, boolean user, String tenantId) {
        Context ctx = context(tenantId);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("ETag", etag(r.version()));
        headers.put("Location", location(user, r.id()));
        return new ScimResponse(status, render(r, user, ctx), headers);
    }

    private String location(boolean user, String id) {
        return config.baseUrl() + (user ? "/Users/" : "/Groups/") + id;
    }

    private ScimResponse create(String tenantId, boolean user, Map<String, Object> body) {
        tenant(tenantId);
        ResourceDef def = def(user);
        checkSchemas(body, def.coreSchema());
        Map<String, Object> attrs = ScimNormalizer.normalize(def, body, false);
        String password = takePassword(tenantId, attrs, user);
        if (!user) {
            canonicalMembers(tenantId, attrs, null);
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        ScimResource r = new ScimResource(tenantId, UUID.randomUUID().toString(), attrs, 1, now, now);
        store(user).insert(r);
        if (password != null) {
            try {
                hooks.onPasswordSet(tenantId, r.id(), password);
            } catch (RuntimeException e) {
                try {
                    hooks.onDeleted(tenantId, r.id());
                    users.delete(tenantId, r.id());
                } catch (RuntimeException rollback) {
                    e.addSuppressed(rollback);
                }
                throw e;
            }
        }
        return single(201, r, user, tenantId);
    }

    private String takePassword(String tenantId, Map<String, Object> attrs, boolean user) {
        Object pw = attrs.remove("password");
        if (!user || pw == null) {
            return null;
        }
        String s = (String) pw;
        if (s.isEmpty() || s.length() > 1024) {
            throw new SsoException("scim.invalid_value");
        }
        try {
            hooks.validatePassword(tenantId, s);
        } catch (SsoException e) {
            throw new SsoException("scim.invalid_value");
        }
        return s;
    }

    private void canonicalMembers(String tenantId, Map<String, Object> attrs, ScimResource current) {
        Object m = attrs.get("members");
        if (m == null) {
            return;
        }
        Set<String> existing = new HashSet<>();
        Object had = current == null ? null : current.attributes().get("members");
        if (had != null) {
            for (Object o : ScimData.list(had)) {
                Object v = ScimData.map(o).get("value");
                if (v instanceof String sv) {
                    existing.add(sv);
                }
            }
        }
        Set<String> seen = new HashSet<>();
        List<Object> out = new ArrayList<>();
        for (Object o : ScimData.list(m)) {
            Map<String, Object> el = ScimData.map(o);
            Object type = el.get("type");
            if (type != null && !"User".equalsIgnoreCase((String) type)) {
                throw new SsoException("scim.invalid_value");
            }
            String value = (String) el.get("value");
            if (users.find(tenantId, value).isEmpty()) {
                if (existing.contains(value)) {
                    continue;
                }
                throw new SsoException("scim.invalid_value");
            }
            if (seen.add(value)) {
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("value", value);
                n.put("type", "User");
                out.add(n);
            }
        }
        if (out.isEmpty()) {
            attrs.remove("members");
        } else {
            attrs.put("members", out);
        }
    }

    private ScimResponse get(String tenantId, boolean user, String id, String ifNoneMatch) {
        tenant(tenantId);
        ScimResource r = load(tenantId, user, id);
        if (ifNoneMatch != null && !ifNoneMatch.isBlank() && ifNoneMatch.length() <= 1024
                && etagMatches(ifNoneMatch, r.version())) {
            return new ScimResponse(304, Map.of(), Map.of("ETag", etag(r.version())));
        }
        return single(200, r, user, tenantId);
    }

    private ScimResponse replace(String tenantId, boolean user, String id, Map<String, Object> body, String ifMatch) {
        tenant(tenantId);
        ResourceDef def = def(user);
        checkSchemas(body, def.coreSchema());
        ScimResource current = load(tenantId, user, id);
        precondition(ifMatch, current);
        Object bodyId = body.get("id");
        if (bodyId != null && !id.equals(bodyId)) {
            throw new SsoException("scim.mutability");
        }
        Map<String, Object> attrs = ScimNormalizer.normalize(def, body, false);
        String password = takePassword(tenantId, attrs, user);
        if (!user) {
            canonicalMembers(tenantId, attrs, current);
        }
        return commit(tenantId, user, current, attrs, password);
    }

    private ScimResponse commit(String tenantId, boolean user, ScimResource current, Map<String, Object> attrs,
            String password) {
        boolean changed = !attrs.equals(current.attributes());
        boolean deactivating = user && active(current.attributes()) && !active(attrs);
        if (deactivating) {
            hooks.onDeactivated(tenantId, current.id());
        }
        ScimResource saved = current;
        if (changed) {
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            ScimResource next = current.withAttributes(attrs, now);
            saved = store(user).update(next, current.version());
        }
        if (user) {
            if (password != null) {
                hooks.onPasswordSet(tenantId, saved.id(), password);
            }
        }
        return single(200, saved, user, tenantId);
    }

    private ScimResponse patch(String tenantId, boolean user, String id, Map<String, Object> body, String ifMatch) {
        tenant(tenantId);
        ResourceDef def = def(user);
        checkSchemas(body, ScimSchemas.PATCH_OP);
        ScimResource current = load(tenantId, user, id);
        precondition(ifMatch, current);
        Map<String, Object> doc = ScimData.mutableCopy(current.attributes());
        Object ops = null;
        for (Map.Entry<String, Object> e : body.entrySet()) {
            if (e.getKey().equalsIgnoreCase("Operations")) {
                ops = e.getValue();
            }
        }
        ScimPatch.apply(def, config.filterLimits(), doc, ops, config.maxPatchOperations());
        Map<String, Object> attrs = ScimNormalizer.normalize(def, doc, true);
        String password = takePassword(tenantId, attrs, user);
        if (!user) {
            canonicalMembers(tenantId, attrs, current);
        }
        return commit(tenantId, user, current, attrs, password);
    }

    private ScimResponse delete(String tenantId, boolean user, String id, String ifMatch) {
        tenant(tenantId);
        ScimResource current = load(tenantId, user, id);
        precondition(ifMatch, current);
        if (user) {
            hooks.onDeleted(tenantId, id);
            store(user).delete(tenantId, id);
        } else if (!store(user).delete(tenantId, id)) {
            throw new SsoException("scim.not_found");
        }
        if (user) {
            List<ScimResource> all;
            try {
                all = groups.list(tenantId);
            } catch (RuntimeException e) {
                all = List.of();
            }
            for (ScimResource g : all) {
                try {
                    removeMember(tenantId, g, id);
                } catch (RuntimeException e) {
                    continue;
                }
            }
        }
        return new ScimResponse(204, Map.of(), Map.of());
    }

    private void removeMember(String tenantId, ScimResource group, String userId) {
        ScimResource g = group;
        for (int attempt = 0; attempt < 3 && g != null; attempt++) {
            Object m = g.attributes().get("members");
            if (m == null) {
                return;
            }
            List<Object> kept = new ArrayList<>();
            for (Object o : ScimData.list(m)) {
                if (!userId.equals(ScimData.map(o).get("value"))) {
                    kept.add(o);
                }
            }
            if (kept.size() == ScimData.list(m).size()) {
                return;
            }
            Map<String, Object> next = ScimData.mutableCopy(g.attributes());
            if (kept.isEmpty()) {
                next.remove("members");
            } else {
                next.put("members", kept);
            }
            try {
                groups.update(g.withAttributes(next, clock.instant().truncatedTo(ChronoUnit.MILLIS)), g.version());
                return;
            } catch (RuntimeException e) {
                g = groups.find(tenantId, g.id()).orElse(null);
            }
        }
    }

    private record Context(Map<String, ScimResource> usersById, Map<String, List<ScimResource>> groupsByUser) {
    }

    private Context context(String tenantId) {
        Map<String, ScimResource> byId = new HashMap<>();
        for (ScimResource u : users.list(tenantId)) {
            byId.put(u.id(), u);
        }
        Map<String, List<ScimResource>> membership = new HashMap<>();
        for (ScimResource g : groups.list(tenantId)) {
            Object m = g.attributes().get("members");
            if (m instanceof List<?> l) {
                for (Object o : l) {
                    String uid = (String) ((Map<?, ?>) o).get("value");
                    membership.computeIfAbsent(uid, k -> new ArrayList<>()).add(g);
                }
            }
        }
        return new Context(byId, membership);
    }

    private Map<String, Object> render(ScimResource r, boolean user, Context ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> schemas = new ArrayList<>();
        schemas.add(user ? ScimSchemas.USER : ScimSchemas.GROUP);
        for (String urn : def(user).extensions().keySet()) {
            if (r.attributes().containsKey(urn)) {
                schemas.add(urn);
            }
        }
        out.put("schemas", schemas);
        out.put("id", r.id());
        for (Map.Entry<String, Object> e : r.attributes().entrySet()) {
            if (e.getKey().equals("password")) {
                continue;
            }
            if (!user && e.getKey().equals("members")) {
                out.put("members", renderMembers(ScimData.list(e.getValue()), ctx));
            } else {
                out.put(e.getKey(), ScimData.deepCopy(e.getValue()));
            }
        }
        if (user) {
            List<ScimResource> gs = ctx.groupsByUser.get(r.id());
            if (gs != null && !gs.isEmpty()) {
                List<Object> list = new ArrayList<>();
                for (ScimResource g : gs) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("value", g.id());
                    m.put("$ref", location(false, g.id()));
                    m.put("display", g.attributes().get("displayName"));
                    m.put("type", "direct");
                    list.add(m);
                }
                out.put("groups", list);
            }
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("resourceType", user ? "User" : "Group");
        meta.put("created", r.created().toString());
        meta.put("lastModified", r.lastModified().toString());
        meta.put("location", location(user, r.id()));
        meta.put("version", etag(r.version()));
        out.put("meta", meta);
        return out;
    }

    private List<Object> renderMembers(List<Object> members, Context ctx) {
        List<Object> out = new ArrayList<>();
        for (Object o : members) {
            Map<String, Object> src = ScimData.map(o);
            String uid = (String) src.get("value");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("value", uid);
            m.put("$ref", location(true, uid));
            m.put("type", "User");
            ScimResource u = ctx.usersById.get(uid);
            if (u != null) {
                Object dn = u.attributes().get("displayName");
                m.put("display", dn != null ? dn : u.attributes().get("userName"));
            }
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> listBody(List<Object> resources, int total, int start, int perPage) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemas", List.of(ScimSchemas.LIST_RESPONSE));
        m.put("totalResults", total);
        m.put("startIndex", start);
        m.put("itemsPerPage", perPage);
        m.put("Resources", resources);
        return m;
    }

    private ScimResponse list(String tenantId, boolean user, ListQuery q) {
        tenant(tenantId);
        ResourceDef def = def(user);
        FilterNode filter = q.filter() == null || q.filter().isBlank()
                ? null
                : FilterParser.parse(q.filter(), def, config.filterLimits());
        AttrPath sortPath = null;
        if (q.sortBy() != null && !q.sortBy().isBlank()) {
            sortPath = def.resolve(q.sortBy());
            if (sortPath == null || sortPath.leaf().type() == AttrDef.Type.COMPLEX
                    || sortPath.leaf().mutability() == AttrDef.Mutability.WRITE_ONLY) {
                throw new SsoException("scim.invalid_path");
            }
        }
        boolean descending = false;
        if (q.sortOrder() != null) {
            String o = q.sortOrder().toLowerCase(Locale.ROOT);
            if (o.equals("descending")) {
                descending = true;
            } else if (!o.equals("ascending")) {
                throw new SsoException("scim.invalid_value");
            }
        }
        int start = q.startIndex() == null ? 1 : Math.max(1, q.startIndex());
        int count = q.count() == null ? config.defaultCount() : Math.max(0, Math.min(q.count(), config.maxCount()));

        Context ctx = context(tenantId);
        List<ScimResource> all = new ArrayList<>(store(user).list(tenantId));
        all.sort(Comparator.comparing(ScimResource::created).thenComparing(ScimResource::id));
        List<Map<String, Object>> views = new ArrayList<>();
        for (ScimResource r : all) {
            Map<String, Object> view = render(r, user, ctx);
            if (filter == null || FilterEvaluator.matches(filter, view)) {
                views.add(view);
            }
        }
        if (sortPath != null) {
            views.sort(sorter(sortPath, descending));
        }
        int total = views.size();
        List<Object> page = new ArrayList<>();
        long from = (long) start - 1;
        for (long i = from; i < total && page.size() < count; i++) {
            page.add(project(views.get((int) i), q.attributes(), q.excludedAttributes()));
        }
        return new ScimResponse(200, listBody(page, total, start, page.size()), Map.of());
    }

    private Comparator<Map<String, Object>> sorter(AttrPath path, boolean descending) {
        Comparator<Map<String, Object>> base = (a, b) -> {
            Object x = sortValue(path, a);
            Object y = sortValue(path, b);
            if (x == null && y == null) {
                return 0;
            }
            if (x == null) {
                return 1;
            }
            if (y == null) {
                return -1;
            }
            return compareValues(path.leaf(), x, y);
        };
        Comparator<Map<String, Object>> c = descending ? base.reversed() : base;
        return c.thenComparing(m -> (String) m.get("id"));
    }

    private static int compareValues(AttrDef leaf, Object x, Object y) {
        if (x instanceof Boolean bx && y instanceof Boolean by) {
            return Boolean.compare(bx, by);
        }
        if (x instanceof String sx && y instanceof String sy) {
            if (leaf.type() == AttrDef.Type.DATETIME) {
                try {
                    return Instant.parse(sx).compareTo(Instant.parse(sy));
                } catch (RuntimeException e) {
                    return sx.compareTo(sy);
                }
            }
            return leaf.caseExact() ? sx.compareTo(sy) : sx.toLowerCase(Locale.ROOT).compareTo(sy.toLowerCase(Locale.ROOT));
        }
        return 0;
    }

    private static Object sortValue(AttrPath path, Map<String, Object> view) {
        Map<String, Object> top = path.urn() == null
                ? view
                : view.get(path.urn()) instanceof Map<?, ?> m ? ScimData.map(m) : Map.of();
        Object v = top.get(path.attr().name());
        if (path.sub() == null) {
            return v instanceof List<?> l ? (l.isEmpty() ? null : l.get(0)) : v;
        }
        if (v instanceof Map<?, ?> m) {
            return m.get(path.sub().name());
        }
        if (v instanceof List<?> l) {
            Object chosen = null;
            for (Object e : l) {
                if (e instanceof Map<?, ?> m) {
                    if (Boolean.TRUE.equals(m.get("primary"))) {
                        chosen = m;
                        break;
                    }
                    if (chosen == null) {
                        chosen = m;
                    }
                }
            }
            return chosen == null ? null : ((Map<?, ?>) chosen).get(path.sub().name());
        }
        return null;
    }

    private Map<String, Object> project(Map<String, Object> view, List<String> attributes, List<String> excluded) {
        if (attributes.isEmpty() && excluded.isEmpty()) {
            return view;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (!attributes.isEmpty()) {
            for (Map.Entry<String, Object> e : view.entrySet()) {
                String k = e.getKey();
                if (k.equals("schemas") || k.equals("id")) {
                    out.put(k, e.getValue());
                    continue;
                }
                for (String a : attributes) {
                    String t = a.trim();
                    int dot = t.indexOf('.');
                    String head = dot < 0 ? t : t.substring(0, dot);
                    if (head.equalsIgnoreCase(k)) {
                        out.put(k, dot < 0 ? e.getValue() : subset(e.getValue(), t.substring(dot + 1), true));
                        break;
                    }
                }
            }
            return out;
        }
        out.putAll(view);
        for (String a : excluded) {
            String t = a.trim();
            int dot = t.indexOf('.');
            String head = dot < 0 ? t : t.substring(0, dot);
            if (head.equalsIgnoreCase("id") || head.equalsIgnoreCase("schemas")) {
                continue;
            }
            for (String k : new ArrayList<>(out.keySet())) {
                if (k.equalsIgnoreCase(head)) {
                    if (dot < 0) {
                        out.remove(k);
                    } else {
                        out.put(k, subset(out.get(k), t.substring(dot + 1), false));
                    }
                }
            }
        }
        return out;
    }

    private static Object subset(Object v, String sub, boolean keep) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                boolean match = ((String) e.getKey()).equalsIgnoreCase(sub);
                if (match == keep) {
                    out.put((String) e.getKey(), e.getValue());
                }
            }
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object e : l) {
                out.add(subset(e, sub, keep));
            }
            return out;
        }
        return v;
    }

    public Optional<ScimResource> rawUser(String tenantId, String id) {
        tenant(tenantId);
        return users.find(tenantId, id);
    }
}
