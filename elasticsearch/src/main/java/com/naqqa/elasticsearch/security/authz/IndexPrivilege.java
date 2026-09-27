package com.naqqa.elasticsearch.security.authz;

public enum IndexPrivilege {
    ALL,
    READ,
    WRITE,
    INDEX,
    CREATE,
    CREATE_DOC,
    DELETE,
    MANAGE,
    MONITOR,
    VIEW_INDEX_METADATA,
    NONE;

    public boolean implies(String action) {
        return switch (this) {
            case ALL -> true;
            case NONE -> false;
            case READ -> action.startsWith("indices:data/read/");
            case WRITE -> action.startsWith("indices:data/write/");
            case INDEX, CREATE_DOC -> action.equals("indices:data/write/index")
                    || action.equals("indices:data/write/bulk");
            case CREATE -> action.equals("indices:admin/create");
            case DELETE -> action.equals("indices:data/write/delete")
                    || action.equals("indices:data/write/bulk");
            case MANAGE -> action.startsWith("indices:admin/");
            case MONITOR -> action.startsWith("indices:monitor/");
            case VIEW_INDEX_METADATA -> action.equals("indices:admin/get")
                    || action.startsWith("indices:admin/mapping/get")
                    || action.startsWith("indices:admin/aliases/get");
        };
    }
}
