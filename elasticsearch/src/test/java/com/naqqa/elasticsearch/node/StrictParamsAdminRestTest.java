package com.naqqa.elasticsearch.node;

import com.naqqa.elasticsearch.test.Test;

import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class StrictParamsAdminRestTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> error(NodeTestSupport.Response response) {
        return (Map<String, Object>) response.json().get("error");
    }

    private static void assertUnrecognizedMasterTimeout(NodeTestSupport.Response response) {
        assertEquals(400, response.status(), response.body());
        Map<String, Object> error = error(response);
        assertEquals("illegal_argument_exception", error.get("type"));
        String reason = String.valueOf(error.get("reason"));
        assertTrue(reason.contains("[master_timeot]"), reason);
        assertTrue(reason.contains("did you mean [master_timeout]?"), reason);
    }

    @Test
    public void testIngestPipelineTypoParamSuggestsMasterTimeout() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("strict-ingest")) {
            NodeTestSupport.Response response = es.request("PUT", "/_ingest/pipeline/p1?master_timeot=30s",
                "{\"processors\":[]}");
            assertUnrecognizedMasterTimeout(response);
        }
    }

    @Test
    public void testSnapshotRepositoryTypoParamSuggestsMasterTimeout() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("strict-snapshot")) {
            NodeTestSupport.Response response = es.request("PUT", "/_snapshot/repo1?master_timeot=30s",
                "{\"type\":\"fs\",\"settings\":{\"location\":\"repo1\"}}");
            assertUnrecognizedMasterTimeout(response);
        }
    }

    @Test
    public void testIlmPolicyTypoParamSuggestsMasterTimeout() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("strict-ilm")) {
            NodeTestSupport.Response response = es.request("PUT", "/_ilm/policy/p1?master_timeot=30s",
                "{\"policy\":{\"phases\":{}}}");
            assertUnrecognizedMasterTimeout(response);
        }
    }

    @Test
    public void testSecurityUserTypoParamSuggestsMasterTimeout() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("strict-security")) {
            NodeTestSupport.Response response = es.request("PUT", "/_security/user/bob?master_timeot=30s",
                "{\"password\":\"secretpass\",\"roles\":[\"viewer\"]}");
            assertUnrecognizedMasterTimeout(response);
        }
    }

    @Test
    public void testSlmPolicyTypoParamSuggestsMasterTimeout() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("strict-slm")) {
            NodeTestSupport.Response response = es.request("PUT", "/_slm/policy/p1?master_timeot=30s",
                "{\"schedule\":\"interval:PT1H\",\"name\":\"<snap-{now}>\",\"repository\":\"backup\"}");
            assertUnrecognizedMasterTimeout(response);
        }
    }

    @Test
    public void testSecurityRoleMappingTypoParamSuggestsMasterTimeout() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("strict-role-mapping")) {
            NodeTestSupport.Response response = es.request("PUT", "/_security/role_mapping/m1?master_timeot=30s",
                "{\"roles\":[\"viewer\"],\"rules\":{\"field\":{\"username\":\"bob\"}}}");
            assertUnrecognizedMasterTimeout(response);
        }
    }
}
