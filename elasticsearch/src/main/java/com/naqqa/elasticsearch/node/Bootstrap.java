package com.naqqa.elasticsearch.node;

import com.naqqa.elasticsearch.common.logging.ESLogger;
import com.naqqa.elasticsearch.common.logging.LogConfigurator;
import com.naqqa.elasticsearch.common.settings.PropertiesSettingsLoader;
import com.naqqa.elasticsearch.common.settings.Settings;
import com.naqqa.elasticsearch.common.settings.YamlSettingsLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

public final class Bootstrap {

    private static final ESLogger LOG = ESLogger.getLogger(Bootstrap.class);

    private Bootstrap() {
    }

    public static Settings loadSettings(String[] args) throws IOException {
        Settings.Builder builder = Settings.builder();
        Path configFile = null;
        Settings.Builder overrides = Settings.builder();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("-E")) {
                String kv = arg.substring(2);
                int eq = kv.indexOf('=');
                if (eq <= 0) {
                    throw new IllegalArgumentException("setting [" + arg + "] must be of the form -Ekey=value");
                }
                overrides.put(kv.substring(0, eq), kv.substring(eq + 1));
            } else if ("-c".equals(arg) || "--config".equals(arg)) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException(arg + " requires a path argument");
                }
                configFile = Path.of(args[++i]);
            } else if (!arg.startsWith("-")) {
                configFile = Path.of(arg);
            } else {
                throw new IllegalArgumentException("unknown argument [" + arg + "]");
            }
        }
        if (configFile == null) {
            String env = System.getenv("ES_PATH_CONF");
            Path candidate = env != null ? Path.of(env).resolve("elasticsearch.yml") : Path.of("config", "elasticsearch.yml");
            if (Files.exists(candidate)) {
                configFile = candidate;
            }
        } else if (!Files.exists(configFile)) {
            throw new IllegalArgumentException("configuration file [" + configFile + "] does not exist");
        }
        if (configFile != null) {
            String content = Files.readString(configFile, StandardCharsets.UTF_8);
            String name = configFile.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            Settings fileSettings = name.endsWith(".properties") ? PropertiesSettingsLoader.load(content) : YamlSettingsLoader.load(content);
            builder.put(fileSettings);
            if (fileSettings.get("path.conf") == null && configFile.toAbsolutePath().getParent() != null) {
                builder.put("path.conf", configFile.toAbsolutePath().getParent().toString());
            }
        }
        builder.put(overrides.build());
        return builder.build();
    }

    public static void main(String[] args) throws Exception {
        Settings settings = loadSettings(args);
        LogConfigurator.configure(settings.getAsMap());
        Node node = new Node(settings);
        CountDownLatch stopped = new CountDownLatch(1);
        Thread hook = new Thread(() -> {
            try {
                node.close();
            } finally {
                stopped.countDown();
            }
        }, "es-shutdown-hook");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            node.start();
        } catch (RuntimeException e) {
            LOG.error("[bootstrap] " + e.getMessage());
            Runtime.getRuntime().removeShutdownHook(hook);
            node.close();
            System.exit(1);
            return;
        }
        NodeInfo info = node.nodeInfo();
        LOG.warn("[" + info.name() + "] started: cluster [" + info.clusterName() + "], node id [" + info.id()
            + "], http [" + info.httpAddress() + "], transport [" + info.transportAddress() + "]");
        stopped.await();
    }
}
