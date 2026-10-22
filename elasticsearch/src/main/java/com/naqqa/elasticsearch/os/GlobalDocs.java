package com.naqqa.elasticsearch.os;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GlobalDocs {

    private GlobalDocs() {
    }

    private static final Map<String, String> PROJECT_NAMES = new LinkedHashMap<>();

    static {
        PROJECT_NAMES.put("prj_marketing_site", "Marketing Site");
        PROJECT_NAMES.put("prj_internal_portal", "Internal Portal");
        PROJECT_NAMES.put("prj_client_preview", "Client Preview");
        PROJECT_NAMES.put("prj_omy", "OMY Platform");
        PROJECT_NAMES.put("prj_shield", "AI Clone Shield");
        PROJECT_NAMES.put("prj_ui", "naqqa-ui Library");
    }

    private static final List<String> TASK_TITLES = List.of(
            "Fix login redirect loop", "Add dark theme tokens", "Refactor search layer", "Optimize bundle size",
            "Write SSR smoke test", "Design facet sidebar", "Migrate to naqqa-ui", "Harden rate limiter",
            "Audit accessibility", "Ship analytics overview");

    private static final List<String> CLIENT_NAMES = List.of(
            "Acme Retail", "Globex Media", "Initech Software", "Umbrella Health", "Soylent Foods", "Stark Industries");

    private static final List<String> PAGE_PATHS = List.of(
            "/", "/pricing", "/docs", "/blog/observability-launch", "/contact", "/changelog");

    private static Map<String, Object> doc(String id, String type, String title, String subtitle, String url,
                                           String projectId, String keywords, Long updatedMs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", type);
        m.put("title", title);
        m.put("subtitle", subtitle);
        m.put("url", url);
        m.put("projectId", projectId);
        m.put("keywords", keywords);
        if (updatedMs != null) {
            m.put("updatedMs", updatedMs);
        }
        return m;
    }

    public static List<Map<String, Object>> build() {
        List<Map<String, Object>> docs = new ArrayList<>();
        List<String> projectIds = new ArrayList<>(PROJECT_NAMES.keySet());
        for (String id : projectIds) {
            String name = PROJECT_NAMES.get(id);
            docs.add(doc("g_project_" + id, "project", name, "Project workspace",
                    "/portal/projects/" + id, id, name + " project workspace", null));
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < TASK_TITLES.size(); i++) {
            String title = TASK_TITLES.get(i);
            String projectId = projectIds.get(i % projectIds.size());
            docs.add(doc("g_task_" + i, "task", title, "Task in " + PROJECT_NAMES.get(projectId),
                    "/portal/board?task=g_task_" + i, projectId,
                    title + " task " + PROJECT_NAMES.get(projectId), now - (long) i * 3600000));
        }
        for (int i = 0; i < CLIENT_NAMES.size(); i++) {
            String name = CLIENT_NAMES.get(i);
            String projectId = projectIds.get(i % projectIds.size());
            docs.add(doc("g_client_" + i, "client", name, "CRM client",
                    "/portal/crm/clients/g_client_" + i, projectId, name + " client crm customer", null));
        }
        for (int i = 0; i < PAGE_PATHS.size(); i++) {
            String path = PAGE_PATHS.get(i);
            String projectId = projectIds.get(i % projectIds.size());
            docs.add(doc("g_page_" + i, "page", path, "Page in " + PROJECT_NAMES.get(projectId),
                    "/portal/projects/" + projectId + "/pages" + (path.equals("/") ? "" : path), projectId,
                    path + " page " + PROJECT_NAMES.get(projectId), null));
        }
        return docs;
    }

    public static SearchDataset dataset() {
        return new MapDataset("global", GlobalDocs::build, "id", List.of("title", "subtitle", "keywords"));
    }
}
