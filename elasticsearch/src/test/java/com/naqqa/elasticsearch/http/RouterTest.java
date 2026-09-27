package com.naqqa.elasticsearch.http;

import com.naqqa.elasticsearch.test.Assert;

public final class RouterTest {

    @com.naqqa.elasticsearch.test.Test
    public void matchesNamedParams() {
        Router router = new Router();
        RestHandler docHandler = (req, ch) -> {};
        router.register(RestMethod.GET, "/{index}/_doc/{id}", docHandler);

        RouteResult result = router.route(RestMethod.GET, "/my-index/_doc/123");
        Assert.assertTrue(result.outcome() == RouteResult.Outcome.MATCHED);
        Assert.assertSame(docHandler, result.handler());
        Assert.assertEquals("my-index", result.pathParams().get("index"));
        Assert.assertEquals("123", result.pathParams().get("id"));
    }

    @com.naqqa.elasticsearch.test.Test
    public void mostSpecificPatternWins() {
        Router router = new Router();
        RestHandler wildcard = (req, ch) -> {};
        RestHandler specific = (req, ch) -> {};
        router.register(RestMethod.GET, "/_cat/*", wildcard);
        router.register(RestMethod.GET, "/_cat/indices", specific);

        RouteResult specificMatch = router.route(RestMethod.GET, "/_cat/indices");
        Assert.assertSame(specific, specificMatch.handler());

        RouteResult wildcardMatch = router.route(RestMethod.GET, "/_cat/health");
        Assert.assertSame(wildcard, wildcardMatch.handler());
        Assert.assertEquals("health", wildcardMatch.pathParams().get("*"));
    }

    @com.naqqa.elasticsearch.test.Test
    public void methodNotAllowedReturnsAllowedMethods() {
        Router router = new Router();
        router.register(RestMethod.GET, "/{index}/_doc/{id}", (req, ch) -> {});
        router.register(RestMethod.POST, "/{index}/_doc/{id}", (req, ch) -> {});

        RouteResult result = router.route(RestMethod.DELETE, "/my-index/_doc/1");
        Assert.assertTrue(result.outcome() == RouteResult.Outcome.METHOD_NOT_ALLOWED);
        Assert.assertTrue(result.allowedMethods().contains(RestMethod.GET));
        Assert.assertTrue(result.allowedMethods().contains(RestMethod.POST));
    }

    @com.naqqa.elasticsearch.test.Test
    public void unknownPathIsNotFound() {
        Router router = new Router();
        router.register(RestMethod.GET, "/{index}/_doc/{id}", (req, ch) -> {});
        RouteResult result = router.route(RestMethod.GET, "/totally/unknown/path/here");
        Assert.assertTrue(result.outcome() == RouteResult.Outcome.NOT_FOUND);
    }

    @com.naqqa.elasticsearch.test.Test
    public void headFallsBackToGetHandler() {
        Router router = new Router();
        RestHandler getHandler = (req, ch) -> {};
        router.register(RestMethod.GET, "/{index}/_doc/{id}", getHandler);

        RouteResult result = router.route(RestMethod.HEAD, "/idx/_doc/7");
        Assert.assertTrue(result.outcome() == RouteResult.Outcome.MATCHED);
        Assert.assertSame(getHandler, result.handler());
    }

    @com.naqqa.elasticsearch.test.Test
    public void decodesPathParamsPerSegment() {
        Router router = new Router();
        Router r = router;
        r.register(RestMethod.GET, "/{index}/_doc/{id}", (req, ch) -> {});
        RouteResult result = r.route(RestMethod.GET, "/my%20index/_doc/a%2Fb");
        Assert.assertEquals("my index", result.pathParams().get("index"));
        Assert.assertEquals("a/b", result.pathParams().get("id"));
    }
}
