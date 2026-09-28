package com.naqqa.elasticsearch.node;

import com.naqqa.elasticsearch.common.settings.Settings;
import com.naqqa.elasticsearch.test.Test;

import java.nio.file.Files;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class RoleMappingRestTest {

    private static void ok(NodeTestSupport.Response r) {
        assertTrue(r.status() >= 200 && r.status() < 300, "status " + r.status() + ": " + r.body());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testRoleMappingGrantsRoleAndChangesAuthorizationOutcome() throws Exception {
        Settings secure = Settings.builder().put("xpack.security.enabled", true).put("bootstrap.password", "changeme").build();
        try (NodeTestSupport es = new NodeTestSupport("role-mapping", secure, Files.createTempDirectory("node-role-mapping"))) {
            es.basicAuth("elastic", "changeme");

            ok(es.request("PUT", "/_security/role/writer",
                "{\"cluster\":[],\"indices\":[{\"names\":[\"secured\"],\"privileges\":[\"read\",\"write\"]}]}"));
            ok(es.request("PUT", "/_security/user/reader", "{\"password\":\"readerpass\",\"roles\":[\"viewer\"]}"));
            ok(es.request("PUT", "/secured/_doc/1?refresh=true", "{\"a\":1}"));

            es.basicAuth("reader", "readerpass");
            ok(es.request("GET", "/secured/_search", null));
            NodeTestSupport.Response denied = es.request("PUT", "/secured/_doc/2", "{\"a\":2}");
            assertEquals(403, denied.status(), denied.body());

            es.basicAuth("elastic", "changeme");
            ok(es.request("PUT", "/_security/role_mapping/reader-to-writer",
                "{\"roles\":[\"writer\"],\"enabled\":true,\"rules\":{\"field\":{\"username\":\"reader\"}}}"));

            NodeTestSupport.Response getMapping = es.request("GET", "/_security/role_mapping/reader-to-writer", null);
            ok(getMapping);
            Map<String, Object> mapping = (Map<String, Object>) getMapping.json().get("reader-to-writer");
            assertEquals(true, mapping.get("enabled"));
            assertTrue(((java.util.List<Object>) mapping.get("roles")).contains("writer"), getMapping.body());

            es.basicAuth("reader", "readerpass");
            NodeTestSupport.Response allowed = es.request("PUT", "/secured/_doc/2?refresh=true", "{\"a\":2}");
            assertTrue(allowed.status() == 200 || allowed.status() == 201, allowed.body());

            es.basicAuth("elastic", "changeme");
            NodeTestSupport.Response listAll = es.request("GET", "/_security/role_mapping", null);
            ok(listAll);
            assertTrue(listAll.json().containsKey("reader-to-writer"), listAll.body());

            NodeTestSupport.Response deleted = es.request("DELETE", "/_security/role_mapping/reader-to-writer", null);
            assertEquals(200, deleted.status(), deleted.body());
            assertEquals(Boolean.TRUE, deleted.json().get("found"));

            es.basicAuth("reader", "readerpass");
            NodeTestSupport.Response deniedAgain = es.request("PUT", "/secured/_doc/3", "{\"a\":3}");
            assertEquals(403, deniedAgain.status(), deniedAgain.body());

            es.basicAuth("elastic", "changeme");
            NodeTestSupport.Response missing = es.request("GET", "/_security/role_mapping/reader-to-writer", null);
            assertEquals(404, missing.status(), missing.body());
        }
    }
}
