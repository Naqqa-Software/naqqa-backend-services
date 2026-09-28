package com.naqqa.elasticsearch.rest;

import com.naqqa.elasticsearch.http.RestHandler;
import com.naqqa.elasticsearch.http.RestMethod;
import com.naqqa.elasticsearch.http.Router;
import com.naqqa.elasticsearch.rest.cat.CatRestHandlers;
import com.naqqa.elasticsearch.rest.cluster.ClusterAdminRestHandlers;
import com.naqqa.elasticsearch.rest.document.DocumentRestHandlers;
import com.naqqa.elasticsearch.rest.indices.IndexAdminRestHandlers;
import com.naqqa.elasticsearch.rest.root.RootRestHandler;
import com.naqqa.elasticsearch.rest.search.SearchRestHandlers;
import com.naqqa.elasticsearch.rest.support.CommonParams;
import com.naqqa.elasticsearch.rest.support.StrictParamsFilter;

import java.util.Set;

/**
 * Wires every REST route to its handler and, at the same time, declares the set of extra
 * query-string parameters that route accepts beyond whatever the handler itself reads off the
 * {@code RestRequest} (which is tracked automatically). Anything outside that combined set,
 * plus the always-allowed global params, is rejected with a 400 the way real Elasticsearch does.
 */
public final class RestModule {

    private RestModule() {
    }

    private static final Set<String> NONE = CommonParams.NONE;

    // ---- shared per-endpoint-family declared param sets -------------------------------------

    private static final Set<String> DOC_WRITE_EXTRA = CommonParams.of("wait_for_active_shards", "require_alias");
    private static final Set<String> DOC_DELETE_EXTRA = CommonParams.of("wait_for_active_shards");
    private static final Set<String> DOC_UPDATE_EXTRA = CommonParams.of("routing", "wait_for_active_shards",
        "_source_includes", "_source_excludes");
    private static final Set<String> BULK_EXTRA = CommonParams.of("pipeline", "routing", "require_alias", "timeout",
        "wait_for_active_shards", "list_executed_pipelines");
    private static final Set<String> MGET_EXTRA = CommonParams.of("refresh", "realtime", "stored_fields", "routing",
        "preference", "_source", "_source_includes", "_source_excludes");
    private static final Set<String> BY_QUERY_EXTRA = CommonParams.of("request_cache", "search_type", "sort", "stats",
        "terminate_after", "version", "size");
    private static final Set<String> TERM_VECTORS_EXTRA = CommonParams.of("fields", "field_statistics", "offsets",
        "payloads", "positions", "term_statistics", "routing", "realtime", "version", "version_type", "preference");
    private static final Set<String> MULTI_TERM_VECTORS_EXTRA = CommonParams.union(TERM_VECTORS_EXTRA, CommonParams.of("ids"));

    private static final Set<String> SEARCH_EXTRA = CommonParams.of("stats", "suggest_field", "suggest_mode",
        "suggest_size", "suggest_text");
    private static final Set<String> MSEARCH_EXTRA = CommonParams.of("search_type", "max_concurrent_searches",
        "typed_keys", "pre_filter_shard_size", "max_concurrent_shard_requests", "rest_total_hits_as_int",
        "ccs_minimize_roundtrips");
    private static final Set<String> RANK_EVAL_EXTRA = CommonParams.of("search_type", "allow_no_indices",
        "expand_wildcards", "ignore_unavailable");

    private static final Set<String> INDICES_OPTIONS = CommonParams.INDICES_OPTIONS;
    private static final Set<String> MASTER_TIMEOUT = CommonParams.MASTER_TIMEOUT;
    private static final Set<String> WAIT_ACTIVE_SHARDS = CommonParams.WAIT_ACTIVE_SHARDS;
    private static final Set<String> LOCAL = CommonParams.LOCAL;
    private static final Set<String> FLAT_SETTINGS = CommonParams.FLAT_SETTINGS;
    private static final Set<String> INCLUDE_DEFAULTS = CommonParams.INCLUDE_DEFAULTS;

    private static final Set<String> CREATE_INDEX_PARAMS = CommonParams.union(WAIT_ACTIVE_SHARDS, MASTER_TIMEOUT);
    private static final Set<String> DELETE_INDEX_PARAMS = CommonParams.union(INDICES_OPTIONS, MASTER_TIMEOUT);
    private static final Set<String> EXISTS_INDEX_PARAMS = CommonParams.union(INDICES_OPTIONS, LOCAL);
    private static final Set<String> GET_INDEX_PARAMS = CommonParams.union(INDICES_OPTIONS, LOCAL, FLAT_SETTINGS,
        INCLUDE_DEFAULTS, MASTER_TIMEOUT);
    private static final Set<String> OPEN_CLOSE_INDEX_PARAMS = CommonParams.union(INDICES_OPTIONS, WAIT_ACTIVE_SHARDS,
        MASTER_TIMEOUT);
    private static final Set<String> PUT_MAPPING_PARAMS = CommonParams.union(INDICES_OPTIONS, MASTER_TIMEOUT,
        CommonParams.of("write_index_only"));
    private static final Set<String> GET_MAPPING_PARAMS = CommonParams.union(INDICES_OPTIONS, LOCAL, MASTER_TIMEOUT);
    private static final Set<String> PUT_SETTINGS_PARAMS = CommonParams.union(INDICES_OPTIONS, MASTER_TIMEOUT,
        FLAT_SETTINGS, CommonParams.of("preserve_existing"));
    private static final Set<String> GET_SETTINGS_PARAMS = CommonParams.union(INDICES_OPTIONS, LOCAL, MASTER_TIMEOUT,
        FLAT_SETTINGS, INCLUDE_DEFAULTS);
    private static final Set<String> ALIAS_WRITE_PARAMS = MASTER_TIMEOUT;
    private static final Set<String> ALIAS_READ_PARAMS = CommonParams.union(INDICES_OPTIONS, LOCAL);
    private static final Set<String> REFRESH_PARAMS = INDICES_OPTIONS;
    private static final Set<String> FLUSH_PARAMS = CommonParams.union(INDICES_OPTIONS, CommonParams.of("force", "wait_if_ongoing"));
    private static final Set<String> FORCE_MERGE_PARAMS = CommonParams.union(INDICES_OPTIONS,
        CommonParams.of("flush", "max_num_segments", "only_expunge_deletes"));
    private static final Set<String> CLEAR_CACHE_PARAMS = CommonParams.union(INDICES_OPTIONS,
        CommonParams.of("fielddata", "fields", "query", "request"));
    private static final Set<String> STATS_PARAMS = CommonParams.of("completion_fields", "expand_wildcards",
        "fielddata_fields", "fields", "forbid_closed_indices", "groups", "include_segment_file_sizes",
        "include_unloaded_segments", "level");
    private static final Set<String> SEGMENTS_PARAMS = CommonParams.union(INDICES_OPTIONS, CommonParams.of("verbose"));
    private static final Set<String> RECOVERY_PARAMS = CommonParams.of("active_only", "detailed");
    private static final Set<String> SHARD_STORES_PARAMS = CommonParams.union(INDICES_OPTIONS, CommonParams.of("status"));
    private static final Set<String> DISK_USAGE_PARAMS = CommonParams.union(INDICES_OPTIONS,
        CommonParams.of("flush", "run_expensive_tasks"));
    private static final Set<String> RESOLVE_INDEX_PARAMS = CommonParams.of("expand_wildcards");
    private static final Set<String> ROLLOVER_SHRINK_PARAMS = CommonParams.union(MASTER_TIMEOUT, WAIT_ACTIVE_SHARDS);
    private static final Set<String> FREEZE_PARAMS = CommonParams.union(MASTER_TIMEOUT, WAIT_ACTIVE_SHARDS, INDICES_OPTIONS);
    private static final Set<String> ADD_BLOCK_PARAMS = CommonParams.union(INDICES_OPTIONS, MASTER_TIMEOUT);
    private static final Set<String> PUT_TEMPLATE_PARAMS = CommonParams.of("create", "cause", "master_timeout");
    private static final Set<String> GET_TEMPLATE_PARAMS = CommonParams.union(LOCAL, CommonParams.of("master_timeout", "flat_settings"));
    private static final Set<String> PUT_LEGACY_TEMPLATE_PARAMS = CommonParams.of("create", "order", "cause", "master_timeout");
    private static final Set<String> SIMULATE_INDEX_PARAMS = CommonParams.of("create", "cause");
    private static final Set<String> DATA_STREAM_EXPAND_PARAMS = CommonParams.of("expand_wildcards");

    private static final Set<String> CLUSTER_HEALTH_PARAMS = CommonParams.of("wait_for_status", "timeout",
        "master_timeout", "level", "local", "wait_for_no_relocating_shards", "wait_for_no_initializing_shards",
        "wait_for_nodes", "wait_for_events");
    private static final Set<String> CLUSTER_STATE_PARAMS = CommonParams.union(LOCAL, MASTER_TIMEOUT, FLAT_SETTINGS,
        INDICES_OPTIONS, CommonParams.of("wait_for_metadata_version", "wait_for_timeout"));
    private static final Set<String> CLUSTER_SETTINGS_PARAMS = CommonParams.union(FLAT_SETTINGS, MASTER_TIMEOUT);
    private static final Set<String> PENDING_TASKS_PARAMS = CommonParams.union(LOCAL, CommonParams.of("master_timeout"));
    private static final Set<String> REROUTE_PARAMS = CommonParams.union(MASTER_TIMEOUT, CommonParams.of("metric", "retry_failed"));
    private static final Set<String> ALLOCATION_EXPLAIN_PARAMS = CommonParams.of("include_yes_decisions", "include_disk_info");
    private static final Set<String> VOTING_CONFIG_EXCLUSIONS_PARAMS = CommonParams.of("timeout", "node_name", "node_ids", "node_names");
    private static final Set<String> CLEAR_VOTING_CONFIG_EXCLUSIONS_PARAMS = CommonParams.of("wait_for_removal");
    private static final Set<String> NODES_INFO_PARAMS = CommonParams.of("timeout", "flat_settings");
    private static final Set<String> NODES_STATS_PARAMS = CommonParams.of("timeout", "level", "types", "include_segment_file_sizes");
    private static final Set<String> NODES_HOT_THREADS_PARAMS = CommonParams.of("threads", "interval", "snapshots",
        "type", "timeout", "ignore_idle_threads");
    private static final Set<String> NODES_USAGE_PARAMS = CommonParams.of("timeout");
    private static final Set<String> RELOAD_SECURE_SETTINGS_PARAMS = CommonParams.of("timeout");
    private static final Set<String> LIST_TASKS_PARAMS = CommonParams.of("nodes", "actions", "detailed",
        "parent_task_id", "wait_for_completion", "group_by", "timeout");
    private static final Set<String> GET_TASK_PARAMS = CommonParams.of("wait_for_completion", "timeout");
    private static final Set<String> CANCEL_TASK_PARAMS = CommonParams.of("nodes", "actions", "parent_task_id", "wait_for_completion");

    private static final Set<String> CAT_COMMON_EXTRA = CommonParams.of("master_timeout", "local");
    private static final Set<String> CAT_THREAD_POOL_EXTRA = CommonParams.union(CAT_COMMON_EXTRA, CommonParams.of("size"));
    private static final Set<String> CAT_NODES_EXTRA = CommonParams.union(CAT_COMMON_EXTRA, CommonParams.of("full_id"));
    private static final Set<String> CAT_RECOVERY_EXTRA = CommonParams.union(CAT_COMMON_EXTRA, CommonParams.of("active_only", "detailed"));
    private static final Set<String> CAT_TASKS_EXTRA = CommonParams.union(CAT_COMMON_EXTRA,
        CommonParams.of("nodes", "actions", "parent_task_id", "detailed"));
    private static final Set<String> CAT_SNAPSHOTS_EXTRA = CommonParams.union(CAT_COMMON_EXTRA, CommonParams.of("ignore_unavailable"));

    public static void registerAll(Router router, RestServices services) {
        registerRoot(router, services);
        registerDocument(router, new DocumentRestHandlers(services.documents(),
            services.indices() == null ? null : services.indices()::refresh));
        registerSearch(router, new SearchRestHandlers(services.search()));
        registerIndices(router, new IndexAdminRestHandlers(services.indices()));
        registerCluster(router, new ClusterAdminRestHandlers(services.cluster()));
        registerCat(router, new CatRestHandlers(services.cat()));
    }

    private static void reg(Router router, RestMethod method, String pathPattern, RestHandler handler, Set<String> declaredParams) {
        router.register(method, pathPattern, StrictParamsFilter.wrap(handler, declaredParams));
    }

    private static void registerRoot(Router router, RestServices services) {
        RootRestHandler root = new RootRestHandler(services.clusterName(), services.nodeName());
        reg(router, RestMethod.GET, "/", root::handle, NONE);
    }

    private static void registerDocument(Router router, DocumentRestHandlers h) {
        reg(router, RestMethod.PUT, "/{index}/_doc/{id}", h::index, DOC_WRITE_EXTRA);
        reg(router, RestMethod.POST, "/{index}/_doc/{id}", h::index, DOC_WRITE_EXTRA);
        reg(router, RestMethod.POST, "/{index}/_doc", h::index, DOC_WRITE_EXTRA);
        reg(router, RestMethod.PUT, "/{index}/_create/{id}", h::create, DOC_WRITE_EXTRA);
        reg(router, RestMethod.GET, "/{index}/_doc/{id}", h::get, NONE);
        reg(router, RestMethod.GET, "/{index}/_source/{id}", h::getSource, NONE);
        reg(router, RestMethod.DELETE, "/{index}/_doc/{id}", h::delete, DOC_DELETE_EXTRA);
        reg(router, RestMethod.POST, "/{index}/_update/{id}", h::update, DOC_UPDATE_EXTRA);
        reg(router, RestMethod.POST, "/_bulk", h::bulk, BULK_EXTRA);
        reg(router, RestMethod.PUT, "/_bulk", h::bulk, BULK_EXTRA);
        reg(router, RestMethod.POST, "/{index}/_bulk", h::bulk, BULK_EXTRA);
        reg(router, RestMethod.PUT, "/{index}/_bulk", h::bulk, BULK_EXTRA);
        reg(router, RestMethod.GET, "/_mget", h::mget, MGET_EXTRA);
        reg(router, RestMethod.POST, "/_mget", h::mget, MGET_EXTRA);
        reg(router, RestMethod.GET, "/{index}/_mget", h::mget, MGET_EXTRA);
        reg(router, RestMethod.POST, "/{index}/_mget", h::mget, MGET_EXTRA);
        reg(router, RestMethod.POST, "/{index}/_delete_by_query", h::deleteByQuery, BY_QUERY_EXTRA);
        reg(router, RestMethod.POST, "/{index}/_update_by_query", h::updateByQuery, BY_QUERY_EXTRA);
        reg(router, RestMethod.POST, "/_reindex", h::reindex, NONE);
        reg(router, RestMethod.GET, "/{index}/_termvectors/{id}", h::termVectors, TERM_VECTORS_EXTRA);
        reg(router, RestMethod.GET, "/{index}/_termvectors", h::termVectors, TERM_VECTORS_EXTRA);
        reg(router, RestMethod.POST, "/{index}/_termvectors/{id}", h::termVectors, TERM_VECTORS_EXTRA);
        reg(router, RestMethod.POST, "/{index}/_termvectors", h::termVectors, TERM_VECTORS_EXTRA);
        reg(router, RestMethod.GET, "/_mtermvectors", h::multiTermVectors, MULTI_TERM_VECTORS_EXTRA);
        reg(router, RestMethod.POST, "/_mtermvectors", h::multiTermVectors, MULTI_TERM_VECTORS_EXTRA);
        reg(router, RestMethod.GET, "/{index}/_mtermvectors", h::multiTermVectors, MULTI_TERM_VECTORS_EXTRA);
        reg(router, RestMethod.POST, "/{index}/_mtermvectors", h::multiTermVectors, MULTI_TERM_VECTORS_EXTRA);
        reg(router, RestMethod.POST, "/_delete_by_query/{taskId}/_rethrottle", h::rethrottle, NONE);
        reg(router, RestMethod.POST, "/_update_by_query/{taskId}/_rethrottle", h::rethrottle, NONE);
    }

    private static void registerSearch(Router router, SearchRestHandlers h) {
        reg(router, RestMethod.GET, "/_search", h::search, SEARCH_EXTRA);
        reg(router, RestMethod.POST, "/_search", h::search, SEARCH_EXTRA);
        reg(router, RestMethod.GET, "/{index}/_search", h::search, SEARCH_EXTRA);
        reg(router, RestMethod.POST, "/{index}/_search", h::search, SEARCH_EXTRA);
        reg(router, RestMethod.GET, "/_msearch", h::multiSearch, MSEARCH_EXTRA);
        reg(router, RestMethod.POST, "/_msearch", h::multiSearch, MSEARCH_EXTRA);
        reg(router, RestMethod.GET, "/{index}/_msearch", h::multiSearch, MSEARCH_EXTRA);
        reg(router, RestMethod.POST, "/{index}/_msearch", h::multiSearch, MSEARCH_EXTRA);
        reg(router, RestMethod.GET, "/_count", h::count, SEARCH_EXTRA);
        reg(router, RestMethod.POST, "/_count", h::count, SEARCH_EXTRA);
        reg(router, RestMethod.GET, "/{index}/_count", h::count, SEARCH_EXTRA);
        reg(router, RestMethod.POST, "/{index}/_count", h::count, SEARCH_EXTRA);
        reg(router, RestMethod.GET, "/{index}/_explain/{id}", h::explain, SEARCH_EXTRA);
        reg(router, RestMethod.POST, "/{index}/_explain/{id}", h::explain, SEARCH_EXTRA);
        reg(router, RestMethod.GET, "/_validate/query", h::validateQuery, SEARCH_EXTRA);
        reg(router, RestMethod.POST, "/_validate/query", h::validateQuery, SEARCH_EXTRA);
        reg(router, RestMethod.GET, "/{index}/_validate/query", h::validateQuery, SEARCH_EXTRA);
        reg(router, RestMethod.POST, "/{index}/_validate/query", h::validateQuery, SEARCH_EXTRA);
        reg(router, RestMethod.GET, "/_field_caps", h::fieldCaps, SEARCH_EXTRA);
        reg(router, RestMethod.POST, "/_field_caps", h::fieldCaps, SEARCH_EXTRA);
        reg(router, RestMethod.GET, "/{index}/_field_caps", h::fieldCaps, SEARCH_EXTRA);
        reg(router, RestMethod.POST, "/{index}/_field_caps", h::fieldCaps, SEARCH_EXTRA);
        reg(router, RestMethod.GET, "/_search/scroll", h::scroll, NONE);
        reg(router, RestMethod.POST, "/_search/scroll", h::scroll, NONE);
        reg(router, RestMethod.DELETE, "/_search/scroll", h::clearScroll, NONE);
        reg(router, RestMethod.POST, "/{index}/_pit", h::openPointInTime, SEARCH_EXTRA);
        reg(router, RestMethod.DELETE, "/_pit", h::closePointInTime, NONE);
        reg(router, RestMethod.POST, "/_async_search", h::submitAsyncSearch, SEARCH_EXTRA);
        reg(router, RestMethod.POST, "/{index}/_async_search", h::submitAsyncSearch, SEARCH_EXTRA);
        reg(router, RestMethod.GET, "/_async_search/{id}", h::getAsyncSearch, SEARCH_EXTRA);
        reg(router, RestMethod.DELETE, "/_async_search/{id}", h::deleteAsyncSearch, NONE);
        reg(router, RestMethod.GET, "/_search/template", h::searchTemplate, SEARCH_EXTRA);
        reg(router, RestMethod.POST, "/_search/template", h::searchTemplate, SEARCH_EXTRA);
        reg(router, RestMethod.GET, "/{index}/_search/template", h::searchTemplate, SEARCH_EXTRA);
        reg(router, RestMethod.POST, "/{index}/_search/template", h::searchTemplate, SEARCH_EXTRA);
        reg(router, RestMethod.GET, "/_render/template", h::renderTemplate, NONE);
        reg(router, RestMethod.POST, "/_render/template", h::renderTemplate, NONE);
        reg(router, RestMethod.GET, "/_rank_eval", h::rankEval, RANK_EVAL_EXTRA);
        reg(router, RestMethod.POST, "/_rank_eval", h::rankEval, RANK_EVAL_EXTRA);
        reg(router, RestMethod.GET, "/{index}/_rank_eval", h::rankEval, RANK_EVAL_EXTRA);
        reg(router, RestMethod.POST, "/{index}/_rank_eval", h::rankEval, RANK_EVAL_EXTRA);
        reg(router, RestMethod.GET, "/{index}/_terms_enum", h::termsEnum, NONE);
        reg(router, RestMethod.POST, "/{index}/_terms_enum", h::termsEnum, NONE);
    }

    private static void registerIndices(Router router, IndexAdminRestHandlers h) {
        reg(router, RestMethod.PUT, "/{index}", h::createIndex, CREATE_INDEX_PARAMS);
        reg(router, RestMethod.DELETE, "/{index}", h::deleteIndex, DELETE_INDEX_PARAMS);
        reg(router, RestMethod.HEAD, "/{index}", h::existsIndex, EXISTS_INDEX_PARAMS);
        reg(router, RestMethod.GET, "/{index}", h::getIndex, GET_INDEX_PARAMS);
        reg(router, RestMethod.POST, "/{index}/_open", h::openIndex, OPEN_CLOSE_INDEX_PARAMS);
        reg(router, RestMethod.POST, "/{index}/_close", h::closeIndex, OPEN_CLOSE_INDEX_PARAMS);
        reg(router, RestMethod.PUT, "/{index}/_mapping", h::putMapping, PUT_MAPPING_PARAMS);
        reg(router, RestMethod.POST, "/{index}/_mapping", h::putMapping, PUT_MAPPING_PARAMS);
        reg(router, RestMethod.GET, "/{index}/_mapping", h::getMapping, GET_MAPPING_PARAMS);
        reg(router, RestMethod.GET, "/_mapping", h::getMapping, GET_MAPPING_PARAMS);
        reg(router, RestMethod.PUT, "/{index}/_settings", h::putSettings, PUT_SETTINGS_PARAMS);
        reg(router, RestMethod.GET, "/{index}/_settings", h::getSettings, GET_SETTINGS_PARAMS);
        reg(router, RestMethod.GET, "/_settings", h::getSettings, GET_SETTINGS_PARAMS);
        reg(router, RestMethod.PUT, "/{index}/_alias/{alias}", h::putAlias, ALIAS_WRITE_PARAMS);
        reg(router, RestMethod.PUT, "/{index}/_aliases/{alias}", h::putAlias, ALIAS_WRITE_PARAMS);
        reg(router, RestMethod.DELETE, "/{index}/_alias/{alias}", h::deleteAlias, ALIAS_WRITE_PARAMS);
        reg(router, RestMethod.DELETE, "/{index}/_aliases/{alias}", h::deleteAlias, ALIAS_WRITE_PARAMS);
        reg(router, RestMethod.HEAD, "/{index}/_alias/{alias}", h::existsAlias, ALIAS_READ_PARAMS);
        reg(router, RestMethod.HEAD, "/_alias/{alias}", h::existsAlias, ALIAS_READ_PARAMS);
        reg(router, RestMethod.GET, "/{index}/_alias/{alias}", h::getAlias, ALIAS_READ_PARAMS);
        reg(router, RestMethod.GET, "/{index}/_alias", h::getAlias, ALIAS_READ_PARAMS);
        reg(router, RestMethod.GET, "/_alias/{alias}", h::getAlias, ALIAS_READ_PARAMS);
        reg(router, RestMethod.GET, "/_alias", h::getAlias, ALIAS_READ_PARAMS);
        reg(router, RestMethod.POST, "/_aliases", h::updateAliases, ALIAS_WRITE_PARAMS);
        reg(router, RestMethod.POST, "/{index}/_refresh", h::refresh, REFRESH_PARAMS);
        reg(router, RestMethod.POST, "/_refresh", h::refresh, REFRESH_PARAMS);
        reg(router, RestMethod.POST, "/{index}/_flush", h::flush, FLUSH_PARAMS);
        reg(router, RestMethod.POST, "/_flush", h::flush, FLUSH_PARAMS);
        reg(router, RestMethod.POST, "/{index}/_forcemerge", h::forceMerge, FORCE_MERGE_PARAMS);
        reg(router, RestMethod.POST, "/_forcemerge", h::forceMerge, FORCE_MERGE_PARAMS);
        reg(router, RestMethod.POST, "/{index}/_cache/clear", h::clearCache, CLEAR_CACHE_PARAMS);
        reg(router, RestMethod.POST, "/_cache/clear", h::clearCache, CLEAR_CACHE_PARAMS);
        reg(router, RestMethod.GET, "/{index}/_stats", h::stats, STATS_PARAMS);
        reg(router, RestMethod.GET, "/_stats", h::stats, STATS_PARAMS);
        reg(router, RestMethod.GET, "/{index}/_segments", h::segments, SEGMENTS_PARAMS);
        reg(router, RestMethod.GET, "/_segments", h::segments, SEGMENTS_PARAMS);
        reg(router, RestMethod.GET, "/{index}/_recovery", h::recovery, RECOVERY_PARAMS);
        reg(router, RestMethod.GET, "/_recovery", h::recovery, RECOVERY_PARAMS);
        reg(router, RestMethod.GET, "/{index}/_shard_stores", h::shardStores, SHARD_STORES_PARAMS);
        reg(router, RestMethod.GET, "/_shard_stores", h::shardStores, SHARD_STORES_PARAMS);
        reg(router, RestMethod.POST, "/{index}/_disk_usage", h::diskUsage, DISK_USAGE_PARAMS);
        reg(router, RestMethod.GET, "/_resolve/index/{name}", h::resolveIndex, RESOLVE_INDEX_PARAMS);
        reg(router, RestMethod.POST, "/{alias}/_rollover", h::rollover, ROLLOVER_SHRINK_PARAMS);
        reg(router, RestMethod.POST, "/{alias}/_rollover/{target}", h::rollover, ROLLOVER_SHRINK_PARAMS);
        reg(router, RestMethod.PUT, "/{index}/_shrink/{target}", h::shrink, ROLLOVER_SHRINK_PARAMS);
        reg(router, RestMethod.PUT, "/{index}/_split/{target}", h::split, ROLLOVER_SHRINK_PARAMS);
        reg(router, RestMethod.PUT, "/{index}/_clone/{target}", h::clone, ROLLOVER_SHRINK_PARAMS);
        reg(router, RestMethod.POST, "/{index}/_freeze", h::freeze, FREEZE_PARAMS);
        reg(router, RestMethod.POST, "/{index}/_unfreeze", h::unfreeze, FREEZE_PARAMS);
        reg(router, RestMethod.PUT, "/{index}/_block/{block}", h::addBlock, ADD_BLOCK_PARAMS);
        reg(router, RestMethod.PUT, "/_index_template/{name}", h::putIndexTemplate, PUT_TEMPLATE_PARAMS);
        reg(router, RestMethod.GET, "/_index_template", h::getIndexTemplate, GET_TEMPLATE_PARAMS);
        reg(router, RestMethod.GET, "/_index_template/{name}", h::getIndexTemplate, GET_TEMPLATE_PARAMS);
        reg(router, RestMethod.DELETE, "/_index_template/{name}", h::deleteIndexTemplate, MASTER_TIMEOUT);
        reg(router, RestMethod.PUT, "/_component_template/{name}", h::putComponentTemplate, PUT_TEMPLATE_PARAMS);
        reg(router, RestMethod.GET, "/_component_template", h::getComponentTemplate, GET_TEMPLATE_PARAMS);
        reg(router, RestMethod.GET, "/_component_template/{name}", h::getComponentTemplate, GET_TEMPLATE_PARAMS);
        reg(router, RestMethod.DELETE, "/_component_template/{name}", h::deleteComponentTemplate, MASTER_TIMEOUT);
        reg(router, RestMethod.PUT, "/_template/{name}", h::putLegacyTemplate, PUT_LEGACY_TEMPLATE_PARAMS);
        reg(router, RestMethod.GET, "/_template", h::getLegacyTemplate, GET_TEMPLATE_PARAMS);
        reg(router, RestMethod.GET, "/_template/{name}", h::getLegacyTemplate, GET_TEMPLATE_PARAMS);
        reg(router, RestMethod.DELETE, "/_template/{name}", h::deleteLegacyTemplate, MASTER_TIMEOUT);
        reg(router, RestMethod.POST, "/_index_template/_simulate_index/{name}", h::simulateIndex, SIMULATE_INDEX_PARAMS);
        reg(router, RestMethod.PUT, "/_data_stream/{name}", h::createDataStream, MASTER_TIMEOUT);
        reg(router, RestMethod.DELETE, "/_data_stream/{name}", h::deleteDataStream, DATA_STREAM_EXPAND_PARAMS);
        reg(router, RestMethod.GET, "/_data_stream", h::getDataStreams, DATA_STREAM_EXPAND_PARAMS);
        reg(router, RestMethod.GET, "/_data_stream/{name}", h::getDataStreams, DATA_STREAM_EXPAND_PARAMS);
    }

    private static void registerCluster(Router router, ClusterAdminRestHandlers h) {
        reg(router, RestMethod.GET, "/_cluster/health", h::health, CLUSTER_HEALTH_PARAMS);
        reg(router, RestMethod.GET, "/_cluster/health/{index}", h::health, CLUSTER_HEALTH_PARAMS);
        reg(router, RestMethod.GET, "/_cluster/state", h::state, CLUSTER_STATE_PARAMS);
        reg(router, RestMethod.GET, "/_cluster/state/{metrics}", h::state, CLUSTER_STATE_PARAMS);
        reg(router, RestMethod.GET, "/_cluster/state/{metrics}/{index}", h::state, CLUSTER_STATE_PARAMS);
        reg(router, RestMethod.GET, "/_cluster/stats", h::stats, MASTER_TIMEOUT);
        reg(router, RestMethod.GET, "/_cluster/settings", h::getSettings, CLUSTER_SETTINGS_PARAMS);
        reg(router, RestMethod.PUT, "/_cluster/settings", h::putSettings, CLUSTER_SETTINGS_PARAMS);
        reg(router, RestMethod.GET, "/_cluster/pending_tasks", h::pendingTasks, PENDING_TASKS_PARAMS);
        reg(router, RestMethod.POST, "/_cluster/reroute", h::reroute, REROUTE_PARAMS);
        reg(router, RestMethod.GET, "/_cluster/allocation/explain", h::allocationExplain, ALLOCATION_EXPLAIN_PARAMS);
        reg(router, RestMethod.POST, "/_cluster/allocation/explain", h::allocationExplain, ALLOCATION_EXPLAIN_PARAMS);
        reg(router, RestMethod.POST, "/_cluster/voting_config_exclusions", h::addVotingConfigExclusions, VOTING_CONFIG_EXCLUSIONS_PARAMS);
        reg(router, RestMethod.DELETE, "/_cluster/voting_config_exclusions", h::clearVotingConfigExclusions, CLEAR_VOTING_CONFIG_EXCLUSIONS_PARAMS);
        reg(router, RestMethod.GET, "/_nodes", h::nodesInfo, NODES_INFO_PARAMS);
        reg(router, RestMethod.GET, "/_nodes/stats", h::nodesStats, NODES_STATS_PARAMS);
        reg(router, RestMethod.GET, "/_nodes/hot_threads", h::nodesHotThreads, NODES_HOT_THREADS_PARAMS);
        reg(router, RestMethod.GET, "/_nodes/usage", h::nodesUsage, NODES_USAGE_PARAMS);
        reg(router, RestMethod.POST, "/_nodes/reload_secure_settings", h::reloadSecureSettings, RELOAD_SECURE_SETTINGS_PARAMS);
        reg(router, RestMethod.GET, "/_nodes/{nodeId}", h::nodesInfo, NODES_INFO_PARAMS);
        reg(router, RestMethod.GET, "/_nodes/{nodeId}/{metrics}", h::nodesInfo, NODES_INFO_PARAMS);
        reg(router, RestMethod.GET, "/_nodes/{nodeId}/stats", h::nodesStats, NODES_STATS_PARAMS);
        reg(router, RestMethod.GET, "/_nodes/{nodeId}/stats/{metrics}", h::nodesStats, NODES_STATS_PARAMS);
        reg(router, RestMethod.GET, "/_nodes/{nodeId}/hot_threads", h::nodesHotThreads, NODES_HOT_THREADS_PARAMS);
        reg(router, RestMethod.GET, "/_nodes/{nodeId}/usage", h::nodesUsage, NODES_USAGE_PARAMS);
        reg(router, RestMethod.POST, "/_nodes/{nodeId}/reload_secure_settings", h::reloadSecureSettings, RELOAD_SECURE_SETTINGS_PARAMS);
        reg(router, RestMethod.GET, "/_tasks", h::listTasks, LIST_TASKS_PARAMS);
        reg(router, RestMethod.GET, "/_tasks/{taskId}", h::getTask, GET_TASK_PARAMS);
        reg(router, RestMethod.POST, "/_tasks/{taskId}/_cancel", h::cancelTask, CANCEL_TASK_PARAMS);
    }

    private static void registerCat(Router router, CatRestHandlers h) {
        reg(router, RestMethod.GET, "/_cat/indices", h::indices, CAT_COMMON_EXTRA);
        reg(router, RestMethod.GET, "/_cat/indices/{index}", h::indices, CAT_COMMON_EXTRA);
        reg(router, RestMethod.GET, "/_cat/shards", h::shards, CAT_COMMON_EXTRA);
        reg(router, RestMethod.GET, "/_cat/shards/{index}", h::shards, CAT_COMMON_EXTRA);
        reg(router, RestMethod.GET, "/_cat/nodes", h::nodes, CAT_NODES_EXTRA);
        reg(router, RestMethod.GET, "/_cat/health", h::health, CAT_COMMON_EXTRA);
        reg(router, RestMethod.GET, "/_cat/allocation", h::allocation, CAT_COMMON_EXTRA);
        reg(router, RestMethod.GET, "/_cat/allocation/{nodeId}", h::allocation, CAT_COMMON_EXTRA);
        reg(router, RestMethod.GET, "/_cat/count", h::count, CAT_COMMON_EXTRA);
        reg(router, RestMethod.GET, "/_cat/count/{index}", h::count, CAT_COMMON_EXTRA);
        reg(router, RestMethod.GET, "/_cat/aliases", h::aliases, CAT_COMMON_EXTRA);
        reg(router, RestMethod.GET, "/_cat/aliases/{alias}", h::aliases, CAT_COMMON_EXTRA);
        reg(router, RestMethod.GET, "/_cat/segments", h::segments, CAT_COMMON_EXTRA);
        reg(router, RestMethod.GET, "/_cat/segments/{index}", h::segments, CAT_COMMON_EXTRA);
        reg(router, RestMethod.GET, "/_cat/recovery", h::recovery, CAT_RECOVERY_EXTRA);
        reg(router, RestMethod.GET, "/_cat/recovery/{index}", h::recovery, CAT_RECOVERY_EXTRA);
        reg(router, RestMethod.GET, "/_cat/thread_pool", h::threadPool, CAT_THREAD_POOL_EXTRA);
        reg(router, RestMethod.GET, "/_cat/thread_pool/{name}", h::threadPool, CAT_THREAD_POOL_EXTRA);
        reg(router, RestMethod.GET, "/_cat/master", h::master, CAT_COMMON_EXTRA);
        reg(router, RestMethod.GET, "/_cat/plugins", h::plugins, CAT_COMMON_EXTRA);
        reg(router, RestMethod.GET, "/_cat/templates", h::templates, CAT_COMMON_EXTRA);
        reg(router, RestMethod.GET, "/_cat/templates/{name}", h::templates, CAT_COMMON_EXTRA);
        reg(router, RestMethod.GET, "/_cat/fielddata", h::fielddata, CAT_COMMON_EXTRA);
        reg(router, RestMethod.GET, "/_cat/fielddata/{fields}", h::fielddata, CAT_COMMON_EXTRA);
        reg(router, RestMethod.GET, "/_cat/pending_tasks", h::pendingTasks, CAT_COMMON_EXTRA);
        reg(router, RestMethod.GET, "/_cat/tasks", h::tasks, CAT_TASKS_EXTRA);
        reg(router, RestMethod.GET, "/_cat/repositories", h::repositories, CAT_COMMON_EXTRA);
        reg(router, RestMethod.GET, "/_cat/snapshots", h::snapshots, CAT_SNAPSHOTS_EXTRA);
        reg(router, RestMethod.GET, "/_cat/snapshots/{repository}", h::snapshots, CAT_SNAPSHOTS_EXTRA);
    }
}
