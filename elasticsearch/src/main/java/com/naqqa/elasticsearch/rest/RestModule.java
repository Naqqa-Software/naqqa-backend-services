package com.naqqa.elasticsearch.rest;

import com.naqqa.elasticsearch.http.RestMethod;
import com.naqqa.elasticsearch.http.Router;
import com.naqqa.elasticsearch.rest.cat.CatRestHandlers;
import com.naqqa.elasticsearch.rest.cluster.ClusterAdminRestHandlers;
import com.naqqa.elasticsearch.rest.document.DocumentRestHandlers;
import com.naqqa.elasticsearch.rest.indices.IndexAdminRestHandlers;
import com.naqqa.elasticsearch.rest.root.RootRestHandler;
import com.naqqa.elasticsearch.rest.search.SearchRestHandlers;

public final class RestModule {

    private RestModule() {
    }

    public static void registerAll(Router router, RestServices services) {
        registerRoot(router, services);
        registerDocument(router, new DocumentRestHandlers(services.documents()));
        registerSearch(router, new SearchRestHandlers(services.search()));
        registerIndices(router, new IndexAdminRestHandlers(services.indices()));
        registerCluster(router, new ClusterAdminRestHandlers(services.cluster()));
        registerCat(router, new CatRestHandlers(services.cat()));
    }

    private static void registerRoot(Router router, RestServices services) {
        RootRestHandler root = new RootRestHandler(services.clusterName(), services.nodeName());
        router.register(RestMethod.GET, "/", root::handle);
    }

    private static void registerDocument(Router router, DocumentRestHandlers h) {
        router.register(RestMethod.PUT, "/{index}/_doc/{id}", h::index);
        router.register(RestMethod.POST, "/{index}/_doc/{id}", h::index);
        router.register(RestMethod.POST, "/{index}/_doc", h::index);
        router.register(RestMethod.PUT, "/{index}/_create/{id}", h::create);
        router.register(RestMethod.GET, "/{index}/_doc/{id}", h::get);
        router.register(RestMethod.GET, "/{index}/_source/{id}", h::getSource);
        router.register(RestMethod.DELETE, "/{index}/_doc/{id}", h::delete);
        router.register(RestMethod.POST, "/{index}/_update/{id}", h::update);
        router.register(RestMethod.POST, "/_bulk", h::bulk);
        router.register(RestMethod.PUT, "/_bulk", h::bulk);
        router.register(RestMethod.POST, "/{index}/_bulk", h::bulk);
        router.register(RestMethod.PUT, "/{index}/_bulk", h::bulk);
        router.register(RestMethod.GET, "/_mget", h::mget);
        router.register(RestMethod.POST, "/_mget", h::mget);
        router.register(RestMethod.GET, "/{index}/_mget", h::mget);
        router.register(RestMethod.POST, "/{index}/_mget", h::mget);
        router.register(RestMethod.POST, "/{index}/_delete_by_query", h::deleteByQuery);
        router.register(RestMethod.POST, "/{index}/_update_by_query", h::updateByQuery);
        router.register(RestMethod.POST, "/_reindex", h::reindex);
        router.register(RestMethod.GET, "/{index}/_termvectors/{id}", h::termVectors);
        router.register(RestMethod.GET, "/{index}/_termvectors", h::termVectors);
        router.register(RestMethod.POST, "/{index}/_termvectors/{id}", h::termVectors);
        router.register(RestMethod.POST, "/{index}/_termvectors", h::termVectors);
        router.register(RestMethod.GET, "/_mtermvectors", h::multiTermVectors);
        router.register(RestMethod.POST, "/_mtermvectors", h::multiTermVectors);
        router.register(RestMethod.GET, "/{index}/_mtermvectors", h::multiTermVectors);
        router.register(RestMethod.POST, "/{index}/_mtermvectors", h::multiTermVectors);
        router.register(RestMethod.POST, "/_delete_by_query/{taskId}/_rethrottle", h::rethrottle);
        router.register(RestMethod.POST, "/_update_by_query/{taskId}/_rethrottle", h::rethrottle);
    }

    private static void registerSearch(Router router, SearchRestHandlers h) {
        router.register(RestMethod.GET, "/_search", h::search);
        router.register(RestMethod.POST, "/_search", h::search);
        router.register(RestMethod.GET, "/{index}/_search", h::search);
        router.register(RestMethod.POST, "/{index}/_search", h::search);
        router.register(RestMethod.GET, "/_msearch", h::multiSearch);
        router.register(RestMethod.POST, "/_msearch", h::multiSearch);
        router.register(RestMethod.GET, "/{index}/_msearch", h::multiSearch);
        router.register(RestMethod.POST, "/{index}/_msearch", h::multiSearch);
        router.register(RestMethod.GET, "/_count", h::count);
        router.register(RestMethod.POST, "/_count", h::count);
        router.register(RestMethod.GET, "/{index}/_count", h::count);
        router.register(RestMethod.POST, "/{index}/_count", h::count);
        router.register(RestMethod.GET, "/{index}/_explain/{id}", h::explain);
        router.register(RestMethod.POST, "/{index}/_explain/{id}", h::explain);
        router.register(RestMethod.GET, "/_validate/query", h::validateQuery);
        router.register(RestMethod.POST, "/_validate/query", h::validateQuery);
        router.register(RestMethod.GET, "/{index}/_validate/query", h::validateQuery);
        router.register(RestMethod.POST, "/{index}/_validate/query", h::validateQuery);
        router.register(RestMethod.GET, "/_field_caps", h::fieldCaps);
        router.register(RestMethod.POST, "/_field_caps", h::fieldCaps);
        router.register(RestMethod.GET, "/{index}/_field_caps", h::fieldCaps);
        router.register(RestMethod.POST, "/{index}/_field_caps", h::fieldCaps);
        router.register(RestMethod.GET, "/_search/scroll", h::scroll);
        router.register(RestMethod.POST, "/_search/scroll", h::scroll);
        router.register(RestMethod.DELETE, "/_search/scroll", h::clearScroll);
        router.register(RestMethod.POST, "/{index}/_pit", h::openPointInTime);
        router.register(RestMethod.DELETE, "/_pit", h::closePointInTime);
        router.register(RestMethod.POST, "/_async_search", h::submitAsyncSearch);
        router.register(RestMethod.POST, "/{index}/_async_search", h::submitAsyncSearch);
        router.register(RestMethod.GET, "/_async_search/{id}", h::getAsyncSearch);
        router.register(RestMethod.DELETE, "/_async_search/{id}", h::deleteAsyncSearch);
        router.register(RestMethod.GET, "/_search/template", h::searchTemplate);
        router.register(RestMethod.POST, "/_search/template", h::searchTemplate);
        router.register(RestMethod.GET, "/{index}/_search/template", h::searchTemplate);
        router.register(RestMethod.POST, "/{index}/_search/template", h::searchTemplate);
        router.register(RestMethod.GET, "/_render/template", h::renderTemplate);
        router.register(RestMethod.POST, "/_render/template", h::renderTemplate);
        router.register(RestMethod.GET, "/_rank_eval", h::rankEval);
        router.register(RestMethod.POST, "/_rank_eval", h::rankEval);
        router.register(RestMethod.GET, "/{index}/_rank_eval", h::rankEval);
        router.register(RestMethod.POST, "/{index}/_rank_eval", h::rankEval);
        router.register(RestMethod.GET, "/{index}/_terms_enum", h::termsEnum);
        router.register(RestMethod.POST, "/{index}/_terms_enum", h::termsEnum);
    }

    private static void registerIndices(Router router, IndexAdminRestHandlers h) {
        router.register(RestMethod.PUT, "/{index}", h::createIndex);
        router.register(RestMethod.DELETE, "/{index}", h::deleteIndex);
        router.register(RestMethod.HEAD, "/{index}", h::existsIndex);
        router.register(RestMethod.GET, "/{index}", h::getIndex);
        router.register(RestMethod.POST, "/{index}/_open", h::openIndex);
        router.register(RestMethod.POST, "/{index}/_close", h::closeIndex);
        router.register(RestMethod.PUT, "/{index}/_mapping", h::putMapping);
        router.register(RestMethod.POST, "/{index}/_mapping", h::putMapping);
        router.register(RestMethod.GET, "/{index}/_mapping", h::getMapping);
        router.register(RestMethod.GET, "/_mapping", h::getMapping);
        router.register(RestMethod.PUT, "/{index}/_settings", h::putSettings);
        router.register(RestMethod.GET, "/{index}/_settings", h::getSettings);
        router.register(RestMethod.GET, "/_settings", h::getSettings);
        router.register(RestMethod.PUT, "/{index}/_alias/{alias}", h::putAlias);
        router.register(RestMethod.PUT, "/{index}/_aliases/{alias}", h::putAlias);
        router.register(RestMethod.DELETE, "/{index}/_alias/{alias}", h::deleteAlias);
        router.register(RestMethod.DELETE, "/{index}/_aliases/{alias}", h::deleteAlias);
        router.register(RestMethod.HEAD, "/{index}/_alias/{alias}", h::existsAlias);
        router.register(RestMethod.HEAD, "/_alias/{alias}", h::existsAlias);
        router.register(RestMethod.GET, "/{index}/_alias/{alias}", h::getAlias);
        router.register(RestMethod.GET, "/{index}/_alias", h::getAlias);
        router.register(RestMethod.GET, "/_alias/{alias}", h::getAlias);
        router.register(RestMethod.GET, "/_alias", h::getAlias);
        router.register(RestMethod.POST, "/_aliases", h::updateAliases);
        router.register(RestMethod.POST, "/{index}/_refresh", h::refresh);
        router.register(RestMethod.POST, "/_refresh", h::refresh);
        router.register(RestMethod.POST, "/{index}/_flush", h::flush);
        router.register(RestMethod.POST, "/_flush", h::flush);
        router.register(RestMethod.POST, "/{index}/_forcemerge", h::forceMerge);
        router.register(RestMethod.POST, "/_forcemerge", h::forceMerge);
        router.register(RestMethod.POST, "/{index}/_cache/clear", h::clearCache);
        router.register(RestMethod.POST, "/_cache/clear", h::clearCache);
        router.register(RestMethod.GET, "/{index}/_stats", h::stats);
        router.register(RestMethod.GET, "/_stats", h::stats);
        router.register(RestMethod.GET, "/{index}/_segments", h::segments);
        router.register(RestMethod.GET, "/_segments", h::segments);
        router.register(RestMethod.GET, "/{index}/_recovery", h::recovery);
        router.register(RestMethod.GET, "/_recovery", h::recovery);
        router.register(RestMethod.GET, "/{index}/_shard_stores", h::shardStores);
        router.register(RestMethod.GET, "/_shard_stores", h::shardStores);
        router.register(RestMethod.POST, "/{index}/_disk_usage", h::diskUsage);
        router.register(RestMethod.GET, "/_resolve/index/{name}", h::resolveIndex);
        router.register(RestMethod.POST, "/{alias}/_rollover", h::rollover);
        router.register(RestMethod.POST, "/{alias}/_rollover/{target}", h::rollover);
        router.register(RestMethod.PUT, "/{index}/_shrink/{target}", h::shrink);
        router.register(RestMethod.PUT, "/{index}/_split/{target}", h::split);
        router.register(RestMethod.PUT, "/{index}/_clone/{target}", h::clone);
        router.register(RestMethod.POST, "/{index}/_freeze", h::freeze);
        router.register(RestMethod.POST, "/{index}/_unfreeze", h::unfreeze);
        router.register(RestMethod.PUT, "/{index}/_block/{block}", h::addBlock);
        router.register(RestMethod.PUT, "/_index_template/{name}", h::putIndexTemplate);
        router.register(RestMethod.GET, "/_index_template", h::getIndexTemplate);
        router.register(RestMethod.GET, "/_index_template/{name}", h::getIndexTemplate);
        router.register(RestMethod.DELETE, "/_index_template/{name}", h::deleteIndexTemplate);
        router.register(RestMethod.PUT, "/_component_template/{name}", h::putComponentTemplate);
        router.register(RestMethod.GET, "/_component_template", h::getComponentTemplate);
        router.register(RestMethod.GET, "/_component_template/{name}", h::getComponentTemplate);
        router.register(RestMethod.DELETE, "/_component_template/{name}", h::deleteComponentTemplate);
        router.register(RestMethod.PUT, "/_template/{name}", h::putLegacyTemplate);
        router.register(RestMethod.GET, "/_template", h::getLegacyTemplate);
        router.register(RestMethod.GET, "/_template/{name}", h::getLegacyTemplate);
        router.register(RestMethod.DELETE, "/_template/{name}", h::deleteLegacyTemplate);
        router.register(RestMethod.POST, "/_index_template/_simulate_index/{name}", h::simulateIndex);
        router.register(RestMethod.PUT, "/_data_stream/{name}", h::createDataStream);
        router.register(RestMethod.DELETE, "/_data_stream/{name}", h::deleteDataStream);
        router.register(RestMethod.GET, "/_data_stream", h::getDataStreams);
        router.register(RestMethod.GET, "/_data_stream/{name}", h::getDataStreams);
    }

    private static void registerCluster(Router router, ClusterAdminRestHandlers h) {
        router.register(RestMethod.GET, "/_cluster/health", h::health);
        router.register(RestMethod.GET, "/_cluster/health/{index}", h::health);
        router.register(RestMethod.GET, "/_cluster/state", h::state);
        router.register(RestMethod.GET, "/_cluster/state/{metrics}", h::state);
        router.register(RestMethod.GET, "/_cluster/state/{metrics}/{index}", h::state);
        router.register(RestMethod.GET, "/_cluster/stats", h::stats);
        router.register(RestMethod.GET, "/_cluster/settings", h::getSettings);
        router.register(RestMethod.PUT, "/_cluster/settings", h::putSettings);
        router.register(RestMethod.GET, "/_cluster/pending_tasks", h::pendingTasks);
        router.register(RestMethod.POST, "/_cluster/reroute", h::reroute);
        router.register(RestMethod.GET, "/_cluster/allocation/explain", h::allocationExplain);
        router.register(RestMethod.POST, "/_cluster/allocation/explain", h::allocationExplain);
        router.register(RestMethod.POST, "/_cluster/voting_config_exclusions", h::addVotingConfigExclusions);
        router.register(RestMethod.DELETE, "/_cluster/voting_config_exclusions", h::clearVotingConfigExclusions);
        router.register(RestMethod.GET, "/_nodes", h::nodesInfo);
        router.register(RestMethod.GET, "/_nodes/stats", h::nodesStats);
        router.register(RestMethod.GET, "/_nodes/hot_threads", h::nodesHotThreads);
        router.register(RestMethod.GET, "/_nodes/usage", h::nodesUsage);
        router.register(RestMethod.POST, "/_nodes/reload_secure_settings", h::reloadSecureSettings);
        router.register(RestMethod.GET, "/_nodes/{nodeId}", h::nodesInfo);
        router.register(RestMethod.GET, "/_nodes/{nodeId}/{metrics}", h::nodesInfo);
        router.register(RestMethod.GET, "/_nodes/{nodeId}/stats", h::nodesStats);
        router.register(RestMethod.GET, "/_nodes/{nodeId}/stats/{metrics}", h::nodesStats);
        router.register(RestMethod.GET, "/_nodes/{nodeId}/hot_threads", h::nodesHotThreads);
        router.register(RestMethod.GET, "/_nodes/{nodeId}/usage", h::nodesUsage);
        router.register(RestMethod.POST, "/_nodes/{nodeId}/reload_secure_settings", h::reloadSecureSettings);
        router.register(RestMethod.GET, "/_tasks", h::listTasks);
        router.register(RestMethod.GET, "/_tasks/{taskId}", h::getTask);
        router.register(RestMethod.POST, "/_tasks/{taskId}/_cancel", h::cancelTask);
    }

    private static void registerCat(Router router, CatRestHandlers h) {
        router.register(RestMethod.GET, "/_cat/indices", h::indices);
        router.register(RestMethod.GET, "/_cat/indices/{index}", h::indices);
        router.register(RestMethod.GET, "/_cat/shards", h::shards);
        router.register(RestMethod.GET, "/_cat/shards/{index}", h::shards);
        router.register(RestMethod.GET, "/_cat/nodes", h::nodes);
        router.register(RestMethod.GET, "/_cat/health", h::health);
        router.register(RestMethod.GET, "/_cat/allocation", h::allocation);
        router.register(RestMethod.GET, "/_cat/allocation/{nodeId}", h::allocation);
        router.register(RestMethod.GET, "/_cat/count", h::count);
        router.register(RestMethod.GET, "/_cat/count/{index}", h::count);
        router.register(RestMethod.GET, "/_cat/aliases", h::aliases);
        router.register(RestMethod.GET, "/_cat/aliases/{alias}", h::aliases);
        router.register(RestMethod.GET, "/_cat/segments", h::segments);
        router.register(RestMethod.GET, "/_cat/segments/{index}", h::segments);
        router.register(RestMethod.GET, "/_cat/recovery", h::recovery);
        router.register(RestMethod.GET, "/_cat/recovery/{index}", h::recovery);
        router.register(RestMethod.GET, "/_cat/thread_pool", h::threadPool);
        router.register(RestMethod.GET, "/_cat/thread_pool/{name}", h::threadPool);
        router.register(RestMethod.GET, "/_cat/master", h::master);
        router.register(RestMethod.GET, "/_cat/plugins", h::plugins);
        router.register(RestMethod.GET, "/_cat/templates", h::templates);
        router.register(RestMethod.GET, "/_cat/templates/{name}", h::templates);
        router.register(RestMethod.GET, "/_cat/fielddata", h::fielddata);
        router.register(RestMethod.GET, "/_cat/fielddata/{fields}", h::fielddata);
        router.register(RestMethod.GET, "/_cat/pending_tasks", h::pendingTasks);
        router.register(RestMethod.GET, "/_cat/tasks", h::tasks);
        router.register(RestMethod.GET, "/_cat/repositories", h::repositories);
        router.register(RestMethod.GET, "/_cat/snapshots", h::snapshots);
        router.register(RestMethod.GET, "/_cat/snapshots/{repository}", h::snapshots);
    }
}
