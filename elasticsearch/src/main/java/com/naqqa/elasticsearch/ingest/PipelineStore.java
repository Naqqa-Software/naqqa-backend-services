package com.naqqa.elasticsearch.ingest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class PipelineStore {

    private final Map<String, Pipeline> pipelines = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> pipelineConfigs = new ConcurrentHashMap<>();

    public void put(String id, Map<String, Object> config, ProcessorRegistry registry) {
        Pipeline pipeline = PipelineFactory.create(id, new LinkedHashMap<>(config), registry);
        pipelines.put(id, pipeline);
        pipelineConfigs.put(id, config);
    }

    /**
     * Reconciles the local compiled-pipeline cache against the authoritative set of pipeline
     * configurations held in the cluster state, compiling anything new or changed and dropping
     * anything no longer present.
     */
    public synchronized void syncFrom(Map<String, Map<String, Object>> configs, ProcessorRegistry registry) {
        for (String id : new ArrayList<>(pipelineConfigs.keySet())) {
            if (!configs.containsKey(id)) {
                pipelines.remove(id);
                pipelineConfigs.remove(id);
            }
        }
        for (Map.Entry<String, Map<String, Object>> e : configs.entrySet()) {
            Map<String, Object> existing = pipelineConfigs.get(e.getKey());
            if (existing != null && existing.equals(e.getValue())) {
                continue;
            }
            try {
                put(e.getKey(), e.getValue(), registry);
            } catch (RuntimeException ex) {
                System.err.println("[ingest] failed to compile pipeline [" + e.getKey() + "] from cluster state: " + ex);
            }
        }
    }

    public List<String> ids() {
        return new ArrayList<>(pipelineConfigs.keySet());
    }

    public Pipeline get(String id) {
        return pipelines.get(id);
    }

    public Map<String, Object> getConfig(String id) {
        return pipelineConfigs.get(id);
    }

    public boolean delete(String id) {
        pipelineConfigs.remove(id);
        return pipelines.remove(id) != null;
    }

    public Map<String, Pipeline> getMatching(String idPattern) {
        Map<String, Pipeline> result = new LinkedHashMap<>();
        if (!idPattern.contains("*")) {
            Pipeline p = pipelines.get(idPattern);
            if (p != null) {
                result.put(idPattern, p);
            }
            return result;
        }
        Pattern pattern = toGlobPattern(idPattern);
        for (Map.Entry<String, Pipeline> e : pipelines.entrySet()) {
            if (pattern.matcher(e.getKey()).matches()) {
                result.put(e.getKey(), e.getValue());
            }
        }
        return result;
    }

    private static Pattern toGlobPattern(String glob) {
        StringBuilder sb = new StringBuilder();
        for (char c : glob.toCharArray()) {
            if (c == '*') {
                sb.append(".*");
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(sb.toString());
    }
}
