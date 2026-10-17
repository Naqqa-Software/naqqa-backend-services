package com.naqqa.auth.sso.scim;

import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.sso.SsoException;
import com.naqqa.sso.scim.AttrPath;
import com.naqqa.sso.scim.FilterNode;
import com.naqqa.sso.scim.FilterParser;
import com.naqqa.sso.scim.ListQuery;
import com.naqqa.sso.scim.ResourceDef;
import com.naqqa.sso.scim.ScimConfig;
import com.naqqa.sso.scim.ScimErrors;
import com.naqqa.sso.scim.ScimHooks;
import com.naqqa.sso.scim.ScimResource;
import com.naqqa.sso.scim.ScimResponse;
import com.naqqa.sso.scim.ScimSchemas;
import com.naqqa.sso.scim.ScimService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

@Component
@ConditionalOnProperty(prefix = "naqqa.sso", name = {"enabled", "scim.enabled"}, havingValue = "true")
public class ScimEndpoint {

    private final MongoScimUserStore users;
    private final MongoScimGroupStore groups;
    private final Clock clock;
    private final ObjectProvider<ScimHooks> hooks;
    private final ScimConfig config;
    private final int maxScan;
    private final int maxStartIndex;

    public ScimEndpoint(MongoScimUserStore users, MongoScimGroupStore groups, Clock clock, ObjectProvider<ScimHooks> hooks,
                        SsoProperties properties) {
        this.users = users;
        this.groups = groups;
        this.clock = clock;
        this.hooks = hooks;
        SsoProperties.Scim scim = properties.getScim();
        String base = scim.getBaseUrl() != null && !scim.getBaseUrl().isBlank() ? scim.getBaseUrl()
                : (properties.getIssuer() == null ? "" : properties.getIssuer()) + "/scim/v2";
        int max = Math.max(1, Math.min(scim.getMaxResults(), ScimConfig.MAX_COUNT));
        this.config = new ScimConfig(base, max, max, 2048, 512, 16, 100, false);
        this.maxScan = Math.max(1, scim.getMaxScan());
        this.maxStartIndex = Math.max(1, scim.getMaxStartIndex());
    }

    public ScimConfig config() {
        return config;
    }

    private ScimService service(ScimRequestScope scope) {
        return new ScimService(scope.userView(), scope.groupView(), hooks.getIfAvailable(() -> ScimHooks.NONE), config, clock);
    }

    public ScimResponse onUser(Function<ScimService, ScimResponse> action) {
        return action.apply(service(new ScimRequestScope(users, groups, ScimRequestScope.Mode.USER, null, false, maxScan)));
    }

    public ScimResponse onGroup(Function<ScimService, ScimResponse> action) {
        return action.apply(service(new ScimRequestScope(users, groups, ScimRequestScope.Mode.GROUP, null, false, maxScan)));
    }

    static boolean skipRelated(boolean user, ListQuery q) {
        String related = user ? "groups" : "members";
        for (String e : q.excludedAttributes()) {
            if (e.trim().equalsIgnoreCase(related)) {
                return true;
            }
        }
        if (!q.attributes().isEmpty()) {
            for (String a : q.attributes()) {
                String head = a.trim();
                int dot = head.indexOf('.');
                if ((dot < 0 ? head : head.substring(0, dot)).equalsIgnoreCase(related)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    public ScimResponse list(String tenantId, boolean user, ListQuery q) {
        try {
            if (tenantId == null || tenantId.isBlank()) {
                return ScimErrors.of("scim.invalid_value");
            }
            ResourceDef def = user ? ScimSchemas.USER_DEF : ScimSchemas.GROUP_DEF;
            FilterNode filter = q.filter() == null || q.filter().isBlank() ? null : FilterParser.parse(q.filter(), def, config.filterLimits());
            AttrPath sortPath = null;
            if (q.sortBy() != null && !q.sortBy().isBlank()) {
                sortPath = def.resolve(q.sortBy());
                if (sortPath == null) {
                    return ScimErrors.of("scim.invalid_path");
                }
            }
            boolean descending = false;
            if (q.sortOrder() != null) {
                String o = q.sortOrder().toLowerCase(Locale.ROOT);
                if (o.equals("descending")) {
                    descending = true;
                } else if (!o.equals("ascending")) {
                    return ScimErrors.of("scim.invalid_value");
                }
            }
            int start = q.startIndex() == null ? 1 : Math.max(1, q.startIndex());
            if (start > maxStartIndex) {
                return ScimErrors.of("scim.too_many");
            }
            int count = q.count() == null ? config.defaultCount() : Math.max(0, Math.min(q.count(), config.maxCount()));
            ScimQueryPlanner.Plan plan = ScimQueryPlanner.plan(filter);
            Sort sort = ScimQueryPlanner.sort(sortPath, descending, user);
            MongoScimStore store = user ? users : groups;
            ScimRequestScope.Mode mode = user ? ScimRequestScope.Mode.LIST_USERS : ScimRequestScope.Mode.LIST_GROUPS;
            boolean skip = skipRelated(user, q);
            if (plan.exact() && sort != null) {
                long total = store.count(tenantId, plan.criteria());
                List<ScimResource> page = count == 0 || start - 1 >= total ? List.of()
                        : store.query(tenantId, plan.criteria(), sort, start - 1L, count);
                ListQuery pageQuery = new ListQuery(null, q.sortBy(), q.sortOrder(), 1, page.size(), q.attributes(), q.excludedAttributes());
                ScimService service = service(new ScimRequestScope(users, groups, mode, page, skip, maxScan));
                ScimResponse r = user ? service.listUsers(tenantId, pageQuery) : service.listGroups(tenantId, pageQuery);
                if (r.status() != 200) {
                    return r;
                }
                Map<String, Object> body = new LinkedHashMap<>(r.body());
                body.put("totalResults", (int) Math.min(Integer.MAX_VALUE, total));
                body.put("startIndex", start);
                return new ScimResponse(200, body, r.headers());
            }
            List<ScimResource> candidates = store.query(tenantId, plan.criteria(), ScimQueryPlanner.sort(null, false, user), 0, maxScan + 1);
            if (candidates.size() > maxScan) {
                return ScimErrors.of("scim.too_many");
            }
            ListQuery capped = new ListQuery(q.filter(), q.sortBy(), q.sortOrder(), start, count, q.attributes(), q.excludedAttributes());
            ScimService service = service(new ScimRequestScope(users, groups, mode, candidates, false, maxScan));
            return user ? service.listUsers(tenantId, capped) : service.listGroups(tenantId, capped);
        } catch (SsoException e) {
            return ScimErrors.of(e);
        } catch (RuntimeException e) {
            return ScimErrors.of(new IllegalStateException());
        }
    }
}
