package com.naqqa.elasticsearch.http;

import com.naqqa.elasticsearch.test.Assert;

import java.util.List;
import java.util.Map;

public final class RestRequestTest {

    @com.naqqa.elasticsearch.test.Test
    public void parsesBooleanFlagsAndFilterPath() {
        Map<String, List<String>> query = QueryStringParser.parse("pretty&human=false&error_trace=true&filter_path=a,b,-c.d");
        RestRequest request = new RestRequest(RestMethod.GET, "/", "/?", query, new HttpHeaders(), new byte[0]);

        Assert.assertTrue(request.pretty());
        Assert.assertFalse(request.human());
        Assert.assertTrue(request.errorTrace());
        Assert.assertEquals(List.of("a", "b"), request.filterPathInclude());
        Assert.assertEquals(List.of("c.d"), request.filterPathExclude());
    }

    @com.naqqa.elasticsearch.test.Test
    public void paramAsListSplitsCommaOrUsesRepeatedValues() {
        Map<String, List<String>> query = QueryStringParser.parse("fields=a,b,c&tags=x&tags=y");
        RestRequest request = new RestRequest(RestMethod.GET, "/", "/?", query, new HttpHeaders(), new byte[0]);

        Assert.assertEquals(List.of("a", "b", "c"), request.paramAsList("fields"));
        Assert.assertEquals(List.of("x", "y"), request.paramAsList("tags"));
    }

    @com.naqqa.elasticsearch.test.Test
    public void pathParamsTakePrecedenceOverQueryParams() {
        Map<String, List<String>> query = QueryStringParser.parse("id=fromQuery");
        RestRequest request = new RestRequest(RestMethod.GET, "/x/_doc/1", "/x/_doc/1?id=fromQuery", query, new HttpHeaders(), new byte[0]);
        request.setPathParams(Map.of("id", "fromPath"));
        Assert.assertEquals("fromPath", request.param("id"));
    }
}
