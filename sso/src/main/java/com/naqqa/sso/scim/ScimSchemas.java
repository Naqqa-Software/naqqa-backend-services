package com.naqqa.sso.scim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ScimSchemas {
    public static final String USER = "urn:ietf:params:scim:schemas:core:2.0:User";
    public static final String GROUP = "urn:ietf:params:scim:schemas:core:2.0:Group";
    public static final String ENTERPRISE = "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User";
    public static final String LIST_RESPONSE = "urn:ietf:params:scim:api:messages:2.0:ListResponse";
    public static final String ERROR = "urn:ietf:params:scim:api:messages:2.0:Error";
    public static final String PATCH_OP = "urn:ietf:params:scim:api:messages:2.0:PatchOp";
    public static final String SEARCH_REQUEST = "urn:ietf:params:scim:api:messages:2.0:SearchRequest";
    public static final String SERVICE_PROVIDER_CONFIG = "urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig";
    public static final String RESOURCE_TYPE = "urn:ietf:params:scim:schemas:core:2.0:ResourceType";
    public static final String SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Schema";

    private static final AttrDef ID = AttrDef.string("id").readOnly().exact().always().unique();
    private static final AttrDef EXTERNAL_ID = AttrDef.string("externalId").exact();
    private static final AttrDef META = AttrDef.complex("meta",
            AttrDef.string("resourceType").readOnly().exact(),
            AttrDef.of("created", AttrDef.Type.DATETIME).readOnly(),
            AttrDef.of("lastModified", AttrDef.Type.DATETIME).readOnly(),
            AttrDef.of("location", AttrDef.Type.REFERENCE).readOnly().exact(),
            AttrDef.string("version").readOnly().exact()).readOnly();

    private static AttrDef multiSimple(String name) {
        return AttrDef.complex(name,
                AttrDef.string("value").requiredAttr(),
                AttrDef.string("display"),
                AttrDef.string("type"),
                AttrDef.bool("primary")).multi();
    }

    private static final List<AttrDef> USER_ATTRS = List.of(
            ID,
            EXTERNAL_ID,
            META,
            AttrDef.string("userName").requiredAttr().unique(),
            AttrDef.complex("name",
                    AttrDef.string("formatted"),
                    AttrDef.string("familyName"),
                    AttrDef.string("givenName"),
                    AttrDef.string("middleName"),
                    AttrDef.string("honorificPrefix"),
                    AttrDef.string("honorificSuffix")),
            AttrDef.string("displayName"),
            AttrDef.string("nickName"),
            AttrDef.of("profileUrl", AttrDef.Type.REFERENCE),
            AttrDef.string("title"),
            AttrDef.string("userType"),
            AttrDef.string("preferredLanguage"),
            AttrDef.string("locale"),
            AttrDef.string("timezone"),
            AttrDef.bool("active"),
            AttrDef.string("password").writeOnlyNever(),
            multiSimple("emails"),
            multiSimple("phoneNumbers"),
            multiSimple("roles"),
            AttrDef.complex("addresses",
                    AttrDef.string("formatted"),
                    AttrDef.string("streetAddress"),
                    AttrDef.string("locality"),
                    AttrDef.string("region"),
                    AttrDef.string("postalCode"),
                    AttrDef.string("country"),
                    AttrDef.string("type"),
                    AttrDef.bool("primary")).multi(),
            AttrDef.complex("groups",
                    AttrDef.string("value").readOnly().exact(),
                    AttrDef.of("$ref", AttrDef.Type.REFERENCE).readOnly().exact(),
                    AttrDef.string("display").readOnly(),
                    AttrDef.string("type").readOnly()).multi().readOnly());

    private static final List<AttrDef> ENTERPRISE_ATTRS = List.of(
            AttrDef.string("employeeNumber"),
            AttrDef.string("costCenter"),
            AttrDef.string("organization"),
            AttrDef.string("division"),
            AttrDef.string("department"),
            AttrDef.complex("manager",
                    AttrDef.string("value").exact(),
                    AttrDef.of("$ref", AttrDef.Type.REFERENCE).exact(),
                    AttrDef.string("displayName").readOnly()));

    private static final List<AttrDef> GROUP_ATTRS = List.of(
            ID,
            EXTERNAL_ID,
            META,
            AttrDef.string("displayName").requiredAttr().unique(),
            AttrDef.complex("members",
                    AttrDef.string("value").requiredAttr().exact(),
                    AttrDef.of("$ref", AttrDef.Type.REFERENCE).readOnly().exact(),
                    AttrDef.string("display").readOnly(),
                    AttrDef.string("type")).multi());

    public static final ResourceDef USER_DEF = new ResourceDef("User", "/Users", "User Account", USER, USER_ATTRS,
            Map.of(ENTERPRISE, ENTERPRISE_ATTRS));
    public static final ResourceDef GROUP_DEF = new ResourceDef("Group", "/Groups", "Group", GROUP, GROUP_ATTRS,
            Map.of());

    private ScimSchemas() {
    }

    public static List<String> ids() {
        return List.of(USER, ENTERPRISE, GROUP);
    }

    public static Map<String, Object> describe(String id, String baseUrl) {
        List<AttrDef> attrs;
        String name;
        String description;
        switch (id) {
            case USER -> {
                attrs = USER_ATTRS;
                name = "User";
                description = "User Account";
            }
            case ENTERPRISE -> {
                attrs = ENTERPRISE_ATTRS;
                name = "EnterpriseUser";
                description = "Enterprise User";
            }
            case GROUP -> {
                attrs = GROUP_ATTRS;
                name = "Group";
                description = "Group";
            }
            default -> {
                return null;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemas", List.of(SCHEMA));
        out.put("id", id);
        out.put("name", name);
        out.put("description", description);
        List<Object> list = new ArrayList<>();
        for (AttrDef a : attrs) {
            list.add(attr(a));
        }
        out.put("attributes", list);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("resourceType", "Schema");
        meta.put("location", baseUrl + "/Schemas/" + id);
        out.put("meta", meta);
        return out;
    }

    private static Map<String, Object> attr(AttrDef a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", a.name());
        m.put("type", a.type().json());
        m.put("multiValued", a.multiValued());
        m.put("description", a.description());
        m.put("required", a.required());
        m.put("caseExact", a.caseExact());
        m.put("mutability", a.mutability().json());
        m.put("returned", a.returned().json());
        m.put("uniqueness", a.uniqueness().json());
        if (!a.subs().isEmpty()) {
            List<Object> subs = new ArrayList<>();
            for (AttrDef s : a.subs()) {
                subs.add(attr(s));
            }
            m.put("subAttributes", subs);
        }
        return m;
    }
}
