package com.naqqa.elasticsearch.node;

import com.naqqa.elasticsearch.action.byquery.ByQueryActionService;
import com.naqqa.elasticsearch.action.get.ShardGetService;
import com.naqqa.elasticsearch.action.search.SearchCoordinator;
import com.naqqa.elasticsearch.action.search.ShardSearchService;
import com.naqqa.elasticsearch.action.write.DocumentActionServiceImpl;
import com.naqqa.elasticsearch.action.write.RelocationAwareRouter;
import com.naqqa.elasticsearch.action.write.UpdateScriptExecutor;
import com.naqqa.elasticsearch.analysis.registry.AnalysisRegistry;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNodeRole;
import com.naqqa.elasticsearch.cluster.node.NodeIdentity;
import com.naqqa.elasticsearch.cluster.routing.IndexRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.IndexShardRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.common.breaker.CircuitBreakerService;
import com.naqqa.elasticsearch.common.lifecycle.AbstractLifecycleComponent;
import com.naqqa.elasticsearch.common.settings.ClusterSettings;
import com.naqqa.elasticsearch.common.settings.Setting;
import com.naqqa.elasticsearch.common.settings.Settings;
import com.naqqa.elasticsearch.common.threadpool.ThreadPool;
import com.naqqa.elasticsearch.common.unit.TimeValue;
import com.naqqa.elasticsearch.http.HttpServerConfig;
import com.naqqa.elasticsearch.http.HttpServerTransport;
import com.naqqa.elasticsearch.http.Router;
import com.naqqa.elasticsearch.http.jdk.JdkHttpServerTransport;
import com.naqqa.elasticsearch.http.nio.NioHttpServerTransport;
import com.naqqa.elasticsearch.indices.alias.AliasService;
import com.naqqa.elasticsearch.indices.datastream.DataStreamService;
import com.naqqa.elasticsearch.indices.template.TemplateResolver;
import com.naqqa.elasticsearch.indices.template.TemplateService;
import com.naqqa.elasticsearch.ingest.IngestService;
import com.naqqa.elasticsearch.ingest.PipelineStore;
import com.naqqa.elasticsearch.ingest.ProcessorRegistry;
import com.naqqa.elasticsearch.ingest.processor.IngestProcessors;
import com.naqqa.elasticsearch.monitor.health.HealthReport;
import com.naqqa.elasticsearch.monitor.tasks.TaskManager;
import com.naqqa.elasticsearch.node.action.NodeCatActionService;
import com.naqqa.elasticsearch.node.action.NodeClusterAdminActionService;
import com.naqqa.elasticsearch.node.action.NodeDocumentActionService;
import com.naqqa.elasticsearch.node.action.NodeIndexAdminActionService;
import com.naqqa.elasticsearch.node.action.NodeSearchActionService;
import com.naqqa.elasticsearch.node.cluster.ClusterStateManager;
import com.naqqa.elasticsearch.node.cluster.NodeConnections;
import com.naqqa.elasticsearch.node.support.ClusterDocumentActionService;
import com.naqqa.elasticsearch.node.indices.IndicesService;
import com.naqqa.elasticsearch.node.indices.LifecycleService;
import com.naqqa.elasticsearch.node.indices.MetadataIndexService;
import com.naqqa.elasticsearch.node.indices.NodeLifecycleActionExecutor;
import com.naqqa.elasticsearch.node.ingest.ScriptBackedIngestScriptService;
import com.naqqa.elasticsearch.node.monitor.MonitorService;
import com.naqqa.elasticsearch.node.monitor.NodeCounters;
import com.naqqa.elasticsearch.node.rest.NodeRestFilter;
import com.naqqa.elasticsearch.node.rest.NodeRestHandlers;
import com.naqqa.elasticsearch.node.search.QueryFactory;
import com.naqqa.elasticsearch.node.search.SearchEngine;
import com.naqqa.elasticsearch.node.security.SecurityService;
import com.naqqa.elasticsearch.node.snapshots.SnapshotsService;
import com.naqqa.elasticsearch.rest.RestModule;
import com.naqqa.elasticsearch.rest.RestServices;
import com.naqqa.elasticsearch.script.Script;
import com.naqqa.elasticsearch.script.ScriptContext;
import com.naqqa.elasticsearch.script.ScriptService;
import com.naqqa.elasticsearch.security.tls.SSLContextFactory;
import com.naqqa.elasticsearch.security.tls.TlsSettings;
import com.naqqa.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class Node extends AbstractLifecycleComponent {

    public static final Setting<String> NODE_NAME = Setting.simpleString("node.name", s -> defaultNodeName(), Setting.Property.NODE_SCOPE);
    public static final Setting<String> CLUSTER_NAME = Setting.simpleString("cluster.name", "elasticsearch", Setting.Property.NODE_SCOPE);
    public static final Setting<String> PATH_DATA = Setting.simpleString("path.data", "data", Setting.Property.NODE_SCOPE);
    public static final Setting<String> PATH_LOGS = Setting.simpleString("path.logs", "logs", Setting.Property.NODE_SCOPE);
    public static final Setting<String> PATH_CONF = Setting.simpleString("path.conf", "config", Setting.Property.NODE_SCOPE);
    public static final Setting<List<String>> PATH_REPO = Setting.listSetting("path.repo", List.of(), Setting.Property.NODE_SCOPE);
    public static final Setting<String> NETWORK_HOST = Setting.simpleString("network.host", "127.0.0.1", Setting.Property.NODE_SCOPE);
    public static final Setting<Integer> HTTP_PORT = Setting.intSetting("http.port", 9200, Setting.Property.NODE_SCOPE);
    public static final Setting<String> HTTP_TYPE = Setting.simpleString("http.type", "jdk", Setting.Property.NODE_SCOPE);
    public static final Setting<Integer> TRANSPORT_PORT = Setting.intSetting("transport.port", 9300, Setting.Property.NODE_SCOPE);
    public static final Setting<Boolean> SECURITY_ENABLED = Setting.boolSetting("xpack.security.enabled", false, Setting.Property.NODE_SCOPE);
    public static final Setting<Boolean> AUDIT_ENABLED = Setting.boolSetting("xpack.security.audit.enabled", false, Setting.Property.NODE_SCOPE);
    public static final Setting<Boolean> HTTP_SSL_ENABLED = Setting.boolSetting("xpack.security.http.ssl.enabled", false, Setting.Property.NODE_SCOPE);
    public static final Setting<String> HTTP_SSL_KEYSTORE = Setting.simpleString("xpack.security.http.ssl.keystore.path", "", Setting.Property.NODE_SCOPE);
    public static final Setting<String> HTTP_SSL_KEYSTORE_PASSWORD = Setting.simpleString("xpack.security.http.ssl.keystore.password", "",
        Setting.Property.NODE_SCOPE, Setting.Property.FILTERED);
    public static final Setting<String> BOOTSTRAP_PASSWORD = Setting.simpleString("bootstrap.password", "", Setting.Property.NODE_SCOPE,
        Setting.Property.FILTERED);
    public static final Setting<Integer> DEFAULT_SHARDS = Setting.intSetting("index.number_of_shards", 1, Setting.Property.NODE_SCOPE);
    public static final Setting<Integer> DEFAULT_REPLICAS = Setting.intSetting("index.number_of_replicas", 0, Setting.Property.NODE_SCOPE);
    public static final Setting<TimeValue> ILM_POLL_INTERVAL = Setting.timeSetting("indices.lifecycle.poll_interval",
        TimeValue.timeValueMinutes(10), Setting.Property.NODE_SCOPE, Setting.Property.DYNAMIC);
    public static final Setting<TimeValue> ELECTION_TIMEOUT = Setting.timeSetting("discovery.election_timeout",
        TimeValue.timeValueSeconds(30), Setting.Property.NODE_SCOPE);
    public static final Setting<Double> BREAKER_TOTAL_LIMIT = Setting.doubleSetting("indices.breaker.total.limit_ratio", 0.95,
        Setting.Property.NODE_SCOPE);
    public static final Setting<List<String>> DISCOVERY_SEED_HOSTS = Setting.listSetting("discovery.seed_hosts", List.of(),
        Setting.Property.NODE_SCOPE);
    public static final Setting<List<String>> DISCOVERY_SEED_PROVIDERS = Setting.listSetting("discovery.seed_providers", List.of(),
        Setting.Property.NODE_SCOPE);
    public static final Setting<String> DISCOVERY_TYPE = Setting.simpleString("discovery.type", "", Setting.Property.NODE_SCOPE);
    public static final Setting<List<String>> INITIAL_MASTER_NODES = Setting.listSetting("cluster.initial_master_nodes", List.of(),
        Setting.Property.NODE_SCOPE);
    public static final Setting<List<String>> NODE_ROLES = Setting.listSetting("node.roles", List.of("master", "data", "ingest"),
        Setting.Property.NODE_SCOPE);
    public static final Setting<TimeValue> FOLLOWER_CHECK_INTERVAL = Setting.timeSetting("cluster.fault_detection.follower_check.interval",
        TimeValue.timeValueSeconds(1), Setting.Property.NODE_SCOPE);
    public static final Setting<TimeValue> FOLLOWER_CHECK_TIMEOUT = Setting.timeSetting("cluster.fault_detection.follower_check.timeout",
        TimeValue.timeValueSeconds(10), Setting.Property.NODE_SCOPE);
    public static final Setting<Integer> FOLLOWER_CHECK_RETRIES = Setting.intSetting("cluster.fault_detection.follower_check.retry_count",
        3, Setting.Property.NODE_SCOPE);
    public static final Setting<TimeValue> PEER_FIND_INTERVAL = Setting.timeSetting("discovery.find_peers_interval",
        TimeValue.timeValueSeconds(1), Setting.Property.NODE_SCOPE);

    private final Settings settings;
    private final List<AutoCloseable> closeables = new ArrayList<>();

    private volatile NodeInfo nodeInfo;
    private ThreadPool threadPool;
    private CircuitBreakerService breakerService;
    private ClusterSettings clusterSettings;
    private TransportService transportService;
    private NodeConnections nodeConnections;
    private ClusterStateManager clusterStateManager;
    private IndicesService indicesService;
    private HttpServerTransport httpServer;
    private SecurityService securityService;
    private IngestService ingestService;
    private ScriptService scriptService;
    private SnapshotsService snapshotsService;
    private MonitorService monitorService;
    private LifecycleService lifecycleService;
    private TaskManager taskManager;
    private NodeCounters counters;
    private RestServices restServices;
    private ScheduledExecutorService maintenance;
    private ExecutorService adminExecutor;
    private Path dataPath;

    public Node(Settings settings) {
        this.settings = settings == null ? Settings.EMPTY : settings;
    }

    private static String defaultNodeName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException | RuntimeException e) {
            return "node-1";
        }
    }

    public Settings settings() {
        return settings;
    }

    public NodeInfo nodeInfo() {
        return nodeInfo;
    }

    public InetSocketAddress httpAddress() {
        return httpServer.boundAddress();
    }

    public InetSocketAddress transportAddress() {
        return transportService.boundAddress();
    }

    public ClusterStateManager clusterStateManager() {
        return clusterStateManager;
    }

    public NodeConnections nodeConnections() {
        return nodeConnections;
    }

    public IndicesService indicesService() {
        return indicesService;
    }

    public RestServices restServices() {
        return restServices;
    }

    public SecurityService securityService() {
        return securityService;
    }

    public MonitorService monitorService() {
        return monitorService;
    }

    public LifecycleService lifecycleService() {
        return lifecycleService;
    }

    public SnapshotsService snapshotsService() {
        return snapshotsService;
    }

    public IngestService ingestService() {
        return ingestService;
    }

    public ClusterSettings clusterSettings() {
        return clusterSettings;
    }

    private Path resolvePath(String raw) {
        Path p = Path.of(raw);
        return p.isAbsolute() ? p : Path.of(System.getProperty("user.dir")).resolve(p).normalize();
    }

    private <T extends AutoCloseable> T track(T closeable) {
        closeables.add(closeable);
        return closeable;
    }

    @Override
    protected void doStart() {
        try {
            startComponents();
        } catch (Exception e) {
            closeQuietly();
            throw new IllegalStateException("failed to start node: " + e.getMessage(), e);
        }
    }

    private void startComponents() throws Exception {
        long startTime = System.currentTimeMillis();
        String nodeName = NODE_NAME.get(settings);
        String clusterName = CLUSTER_NAME.get(settings);
        String host = NETWORK_HOST.get(settings);
        dataPath = resolvePath(PATH_DATA.get(settings));
        Files.createDirectories(dataPath);
        Path configPath = resolvePath(PATH_CONF.get(settings));
        Path logsPath = resolvePath(PATH_LOGS.get(settings));
        List<Path> repoRoots = new ArrayList<>();
        for (String r : PATH_REPO.get(settings)) {
            if (!r.isBlank()) {
                repoRoots.add(resolvePath(r.trim()));
            }
        }
        if (repoRoots.isEmpty()) {
            repoRoots.add(dataPath.resolve("_repos"));
        }
        String nodeId = NodeIdentity.loadOrCreate(dataPath);

        threadPool = new ThreadPool();
        threadPool.start();
        track(threadPool::close);
        adminExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "es-admin[" + nodeName + "]");
            t.setDaemon(true);
            return t;
        });
        track(adminExecutor::shutdownNow);

        breakerService = new CircuitBreakerService((long) (Runtime.getRuntime().maxMemory() * BREAKER_TOTAL_LIMIT.get(settings)));
        clusterSettings = new ClusterSettings(settings, com.naqqa.elasticsearch.common.settings.AbstractScopedSettings.settingsSet(
            NODE_NAME, CLUSTER_NAME, PATH_DATA, PATH_LOGS, PATH_CONF, PATH_REPO, NETWORK_HOST, HTTP_PORT, HTTP_TYPE, TRANSPORT_PORT,
            SECURITY_ENABLED, AUDIT_ENABLED, HTTP_SSL_ENABLED, HTTP_SSL_KEYSTORE, HTTP_SSL_KEYSTORE_PASSWORD, BOOTSTRAP_PASSWORD,
            DEFAULT_SHARDS, DEFAULT_REPLICAS, ILM_POLL_INTERVAL, ELECTION_TIMEOUT, BREAKER_TOTAL_LIMIT, DISCOVERY_SEED_HOSTS,
            DISCOVERY_SEED_PROVIDERS, DISCOVERY_TYPE, INITIAL_MASTER_NODES, NODE_ROLES, FOLLOWER_CHECK_INTERVAL, FOLLOWER_CHECK_TIMEOUT,
            FOLLOWER_CHECK_RETRIES, PEER_FIND_INTERVAL));

        scriptService = ScriptService.defaults();

        transportService = new TransportService(nodeId, new InetSocketAddress(host, TRANSPORT_PORT.get(settings)), threadPool);
        transportService.start();
        track(transportService::close);
        InetSocketAddress transportBound = transportService.boundAddress();
        String transportAddress = host + ":" + transportBound.getPort();

        Map<String, String> flatSettings = new LinkedHashMap<>();
        for (String key : settings.keySet()) {
            if (key.contains("password")) {
                continue;
            }
            flatSettings.put(key, settings.get(key));
        }
        flatSettings.putIfAbsent("node.name", nodeName);
        flatSettings.putIfAbsent("cluster.name", clusterName);
        flatSettings.putIfAbsent("path.data", dataPath.toString());
        EnumSet<DiscoveryNodeRole> roles = parseRoles(settings);
        List<String> roleNames = new ArrayList<>();
        for (DiscoveryNodeRole role : roles) {
            roleNames.add(role.roleName());
        }
        nodeInfo = new NodeInfo(nodeId, nodeName, clusterName, host, transportAddress, null,
            roleNames, NodeInfo.VERSION, flatSettings);

        DiscoveryNode localNode = new DiscoveryNode(nodeId, nodeName, transportAddress, Map.of(), roles, 1L);
        nodeConnections = new NodeConnections(transportService);
        track(nodeConnections);
        ClusterStateManager.DiscoveryConfig discoveryConfig = discoveryConfig(nodeName, configPath);
        clusterStateManager = new ClusterStateManager(clusterName, localNode, dataPath.resolve("_state"), 20L, discoveryConfig,
            transportService, nodeConnections);
        track(clusterStateManager);
        scriptService.bind(clusterStateManager);

        AnalysisRegistry analysisRegistry = new AnalysisRegistry();
        Path indicesPath = dataPath.resolve("indices");
        indicesService = new IndicesService(indicesPath, nodeId, transportService, analysisRegistry, clusterStateManager,
            nodeConnections, threadPool);
        track(indicesService);
        clusterStateManager.addApplier(indicesService);

        new ShardSearchService(transportService, indicesService::shard);
        new ShardGetService(transportService, indicesService::shard);
        Map<String, com.naqqa.elasticsearch.transport.DiscoveryNode> transportNodes = new java.util.concurrent.ConcurrentHashMap<>();
        transportNodes.put(nodeId, transportService.localNode());
        clusterStateManager.addListener(event -> refreshTransportNodes(event.state(), transportNodes, nodeId));
        SearchCoordinator searchCoordinator = new SearchCoordinator(transportService, transportNodes);

        clusterStateManager.start(ELECTION_TIMEOUT.get(settings).millis());
        if (!clusterStateManager.isMultiNode()) {
            for (com.naqqa.elasticsearch.cluster.state.IndexMetadata imd : clusterStateManager.state().getMetadata().getIndices().values()) {
                if (imd.getState() == com.naqqa.elasticsearch.cluster.state.IndexMetadata.State.OPEN) {
                    try {
                        indicesService.awaitShardsStarted(imd.getIndex(), 30_000L).get(31, TimeUnit.SECONDS);
                    } catch (java.util.concurrent.TimeoutException e) {
                        System.err.println("[node] index [" + imd.getIndex() + "] did not recover within 30s");
                    }
                }
            }
        }

        counters = new NodeCounters();
        taskManager = new TaskManager(nodeId);

        TemplateService templateService = new TemplateService();
        TemplateResolver templateResolver = new TemplateResolver(templateService);
        DataStreamService dataStreamService = new DataStreamService(templateResolver);
        AliasService aliasService = new AliasService();
        MetadataIndexService metadataService = new MetadataIndexService(clusterStateManager, indicesService, analysisRegistry,
            aliasService, DEFAULT_SHARDS.get(settings), DEFAULT_REPLICAS.get(settings), 60_000L);
        metadataService.restoreAliasesFromClusterState();
        clusterStateManager.addListener(event -> {
            // Every node keeps its own local AliasService cache, and only the node that happened to
            // originally handle a create-index/_aliases request populates it directly - being the
            // elected master does not imply having already seen that update, so all nodes (including
            // the master) must re-derive it from the authoritative cluster state metadata.
            if (clusterStateManager.isMultiNode() && event.state().getMetadata() != event.previousState().getMetadata()) {
                metadataService.restoreAliasesFromClusterState();
            }
        });

        NodeIndexAdminActionService indexAdmin = new NodeIndexAdminActionService(metadataService, indicesService, templateService,
            templateResolver, dataStreamService, counters, adminExecutor, indicesPath, 30_000L);
        indexAdmin.syncFromClusterState(clusterStateManager.state().getMetadata());
        clusterStateManager.addListener(indexAdmin);

        RelocationAwareRouter router = new RelocationAwareRouter(clusterStateManager::state, indicesService.replicationGroups());
        UpdateScriptExecutor updateScripts = (scriptDefinition, currentSource) -> {
            Script script = Script.parse(scriptDefinition);
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("_source", new LinkedHashMap<>(currentSource));
            ctx.put("op", "index");
            scriptService.execute(script, ScriptContext.UPDATE, Map.of("ctx", ctx));
            Object src = ctx.get("_source");
            return src instanceof Map<?, ?> m ? com.naqqa.elasticsearch.node.support.SettingsMaps.asMap(m) : currentSource;
        };
        DocumentActionServiceImpl writes = new DocumentActionServiceImpl(router, updateScripts, transportService,
            (shardId, state) -> nodeConnections.get(transportNodeFor(state, shardId, transportNodes)));
        writes.bulkCoordinator().registerShardHandler(transportService);

        PipelineStore pipelineStore = new PipelineStore();
        ProcessorRegistry processorRegistry = new ProcessorRegistry(pipelineStore, new ScriptBackedIngestScriptService(scriptService), null);
        IngestProcessors.registerAll(processorRegistry);
        ingestService = new IngestService(pipelineStore, processorRegistry);
        ingestService.bind(clusterStateManager);

        SearchEngine searchEngine = new SearchEngine(clusterStateManager, indicesService, searchCoordinator,
            new QueryFactory(scriptService), indexAdmin.indexResolver(), counters);
        ByQueryActionService byQuery = new ByQueryActionService(router, new com.naqqa.elasticsearch.action.byquery.ByQuerySearchHooks(
            (index, clause) -> searchEngine.toQuery(List.of(index), clause), searchEngine::searchRequest), taskManager, scriptService);
        NodeSearchActionService searchService = new NodeSearchActionService(searchEngine, scriptService,
            threadPool.executor(ThreadPool.SEARCH), counters);
        track(searchService);
        searchService.setAliasFilters(indexAdmin::aliasFilter);

        NodeDocumentActionService documentService = new NodeDocumentActionService(writes, byQuery, clusterStateManager, ingestService,
            indexAdmin::ensureWriteTarget, counters, threadPool.executor(ThreadPool.WRITE), threadPool.executor(ThreadPool.GET),
            indexAdmin::dataStreamBackingIndices);

        com.naqqa.elasticsearch.node.indices.DynamicMappingService dynamicMappingService =
            new com.naqqa.elasticsearch.node.indices.DynamicMappingService(indicesService, metadataService);
        documentService.setDynamicMappingHook(dynamicMappingService::onDocument);
        documentService.setRefresher(index -> indexAdmin.refresh(List.of(index)).join());
        snapshotsService = new SnapshotsService(clusterStateManager, indicesService, indexAdmin, indicesPath, repoRoots,
            dataPath.resolve("_repositories.json"), transportService, nodeConnections);

        monitorService = new MonitorService(nodeId, nodeName, startTime, List.of(dataPath), counters, indicesService, threadPool,
            transportService, breakerService, taskManager, () -> 0);

        lifecycleService = new LifecycleService(new NodeLifecycleActionExecutor(metadataService, indicesService, indexAdmin),
            clusterStateManager::state);
        lifecycleService.bind(clusterStateManager);

        securityService = new SecurityService(SECURITY_ENABLED.get(settings), configPath, logsPath.resolve("audit.json"),
            AUDIT_ENABLED.get(settings), BOOTSTRAP_PASSWORD.get(settings));
        securityService.bind(clusterStateManager);

        NodeRestFilter.ErrorRenderer errorRenderer = new NodeRestFilter.ErrorRenderer();
        NodeRestFilter filter = new NodeRestFilter(securityService, breakerService, counters, errorRenderer);
        NodeClusterAdminActionService clusterService = new NodeClusterAdminActionService(clusterStateManager, metadataService,
            indicesService, indexAdmin.indexResolver(), monitorService, taskManager, () -> nodeInfo, adminExecutor, filter.usage());
        NodeCatActionService catService = new NodeCatActionService(clusterStateManager, indicesService, indexAdmin.indexResolver(),
            indexAdmin, monitorService, taskManager, snapshotsService, () -> nodeInfo, adminExecutor, dataPath);
        ClusterDocumentActionService routedDocuments = new ClusterDocumentActionService(documentService, clusterStateManager,
            indicesService, transportService, nodeConnections, indexAdmin::dataStreamBackingIndices, indexAdmin::ensureWriteTarget);
        track(routedDocuments);
        restServices = new RestServices(routedDocuments, searchService, indexAdmin, clusterService, catService, clusterName, nodeName);

        Router restRouter = new Router(filter);
        RestModule.registerAll(restRouter, restServices);
        new NodeRestHandlers(ingestService, snapshotsService, lifecycleService, securityService, scriptService,
            this::healthReport, clusterStateManager::state, indexAdmin.indexResolver()).registerAll(restRouter);

        HttpServerConfig httpConfig = new HttpServerConfig().host(host).port(HTTP_PORT.get(settings)).errorRenderer(errorRenderer);
        if (HTTP_SSL_ENABLED.get(settings)) {
            String keystore = HTTP_SSL_KEYSTORE.get(settings);
            if (keystore.isEmpty()) {
                throw new IllegalStateException("[xpack.security.http.ssl.enabled] requires [xpack.security.http.ssl.keystore.path]");
            }
            TlsSettings tls = new TlsSettings().keyStorePath(resolvePath(keystore))
                .keyStorePassword(HTTP_SSL_KEYSTORE_PASSWORD.get(settings).toCharArray());
            httpConfig.sslContext(SSLContextFactory.buildServerContext(tls));
        }
        httpServer = "nio".equals(HTTP_TYPE.get(settings)) ? new NioHttpServerTransport(httpConfig, restRouter)
            : new JdkHttpServerTransport(httpConfig, restRouter);
        httpServer.start();
        track(httpServer);
        InetSocketAddress httpBound = httpServer.boundAddress();
        nodeInfo = nodeInfo.withHttpAddress(host + ":" + httpBound.getPort());

        maintenance = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "es-maintenance[" + nodeName + "]");
            t.setDaemon(true);
            return t;
        });
        track(maintenance::shutdownNow);
        long ilmInterval = Math.max(100L, ILM_POLL_INTERVAL.get(settings).millis());
        maintenance.scheduleWithFixedDelay(lifecycleService::tick, ilmInterval, ilmInterval, TimeUnit.MILLISECONDS);
    }

    private static EnumSet<DiscoveryNodeRole> parseRoles(Settings settings) {
        List<String> raw = listSetting(settings, NODE_ROLES);
        EnumSet<DiscoveryNodeRole> roles = EnumSet.noneOf(DiscoveryNodeRole.class);
        for (String name : raw) {
            roles.add(DiscoveryNodeRole.fromRoleName(name));
        }
        return roles;
    }

    private static List<String> listSetting(Settings settings, Setting<List<String>> setting) {
        List<String> values = settings.getAsList(setting.getKey());
        if (values.isEmpty() && settings.get(setting.getKey()) == null) {
            values = setting.get(settings);
        }
        List<String> out = new ArrayList<>();
        for (String v : values) {
            String trimmed = v.trim();
            if (trimmed.startsWith("[")) {
                trimmed = trimmed.substring(1);
            }
            if (trimmed.endsWith("]")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            trimmed = trimmed.replace("\"", "").trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private ClusterStateManager.DiscoveryConfig discoveryConfig(String nodeName, Path configPath) {
        List<String> seedHosts = listSetting(settings, DISCOVERY_SEED_HOSTS);
        Path seedFile = null;
        if (listSetting(settings, DISCOVERY_SEED_PROVIDERS).contains("file")) {
            seedFile = configPath.resolve("unicast_hosts.txt");
        }
        List<String> initialMasters = listSetting(settings, INITIAL_MASTER_NODES);
        if ("single-node".equals(DISCOVERY_TYPE.get(settings)) || (seedHosts.isEmpty() && seedFile == null)) {
            return new ClusterStateManager.DiscoveryConfig(List.of(), null, initialMasters.isEmpty() ? List.of(nodeName) : initialMasters,
                1000L, 1000L, 10_000L, 3, 30_000L);
        }
        return new ClusterStateManager.DiscoveryConfig(seedHosts, seedFile, initialMasters, PEER_FIND_INTERVAL.get(settings).millis(),
            FOLLOWER_CHECK_INTERVAL.get(settings).millis(), FOLLOWER_CHECK_TIMEOUT.get(settings).millis(),
            FOLLOWER_CHECK_RETRIES.get(settings), 30_000L);
    }

    private static void refreshTransportNodes(ClusterState state, Map<String, com.naqqa.elasticsearch.transport.DiscoveryNode> nodes,
                                              String localNodeId) {
        for (DiscoveryNode node : state.getNodes().getNodes().values()) {
            if (node.getId().equals(localNodeId)) {
                continue;
            }
            try {
                nodes.put(node.getId(), NodeConnections.toTransportNode(node));
            } catch (RuntimeException ignored) {
            }
        }
        nodes.keySet().removeIf(id -> !id.equals(localNodeId) && !state.getNodes().nodeExists(id));
    }

    private static com.naqqa.elasticsearch.transport.DiscoveryNode transportNodeFor(ClusterState state,
                                                                                    com.naqqa.elasticsearch.cluster.routing.ShardId shardId,
                                                                                    Map<String, com.naqqa.elasticsearch.transport.DiscoveryNode> known) {
        IndexRoutingTable irt = state.getRoutingTable().index(shardId.index());
        IndexShardRoutingTable table = irt == null ? null : irt.shard(shardId.id());
        ShardRouting primary = table == null ? null : table.primaryShard();
        if (primary == null || primary.currentNodeId() == null) {
            throw new IllegalStateException("no assigned primary for " + shardId);
        }
        com.naqqa.elasticsearch.transport.DiscoveryNode node = known.get(primary.currentNodeId());
        com.naqqa.elasticsearch.cluster.node.DiscoveryNode current = state.getNodes().get(primary.currentNodeId());
        if (current != null) {
            node = NodeConnections.toTransportNode(current);
        }
        if (node == null) {
            com.naqqa.elasticsearch.cluster.node.DiscoveryNode clusterNode = state.getNodes().get(primary.currentNodeId());
            if (clusterNode == null) {
                throw new IllegalStateException("primary node [" + primary.currentNodeId() + "] for " + shardId + " is unknown");
            }
            String address = clusterNode.getAddress();
            int colon = address.lastIndexOf(':');
            node = new com.naqqa.elasticsearch.transport.DiscoveryNode(clusterNode.getId(), address.substring(0, colon),
                Integer.parseInt(address.substring(colon + 1)));
        }
        return node;
    }

    public HealthReport healthReport() {
        int unassignedPrimaries = 0;
        int unassignedReplicas = 0;
        int initializing = 0;
        int total = 0;
        for (ShardRouting sr : clusterStateManager.state().getRoutingTable().allShards()) {
            total++;
            if (sr.unassigned()) {
                if (sr.primary()) {
                    unassignedPrimaries++;
                } else {
                    unassignedReplicas++;
                }
            } else if (sr.initializing()) {
                initializing++;
            }
        }
        int[] counts = {unassignedPrimaries, unassignedReplicas, initializing, total};
        return monitorService.healthReport(() -> clusterStateManager.state().getNodes().getMasterNodeId() != null, () -> counts);
    }

    @Override
    protected void doStop() {
        if (httpServer != null) {
            try {
                httpServer.close();
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    protected void doClose() {
        closeQuietly();
    }

    private void closeQuietly() {
        for (int i = closeables.size() - 1; i >= 0; i--) {
            try {
                closeables.get(i).close();
            } catch (Exception e) {
                System.err.println("[node] error while closing component: " + e);
            }
        }
        closeables.clear();
    }
}
