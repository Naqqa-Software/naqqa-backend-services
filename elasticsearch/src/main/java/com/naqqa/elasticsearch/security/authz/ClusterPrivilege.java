package com.naqqa.elasticsearch.security.authz;

public enum ClusterPrivilege {
    ALL,
    MONITOR,
    MANAGE,
    MANAGE_SECURITY,
    MANAGE_INDEX_TEMPLATES,
    MANAGE_ILM,
    MANAGE_SLM,
    MANAGE_API_KEY,
    NONE;

    public boolean implies(String action) {
        return switch (this) {
            case ALL -> true;
            case NONE -> false;
            case MONITOR -> action.startsWith("cluster:monitor/");
            case MANAGE -> action.startsWith("cluster:admin/") || action.startsWith("cluster:monitor/");
            case MANAGE_SECURITY -> action.startsWith("cluster:admin/xpack/security/");
            case MANAGE_INDEX_TEMPLATES -> action.startsWith("cluster:admin/index_template")
                    || action.startsWith("cluster:admin/template");
            case MANAGE_ILM -> action.startsWith("cluster:admin/ilm/");
            case MANAGE_SLM -> action.startsWith("cluster:admin/slm/");
            case MANAGE_API_KEY -> action.startsWith("cluster:admin/xpack/security/api_key/");
        };
    }
}
