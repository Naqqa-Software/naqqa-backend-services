package com.naqqa.analytics.web;

import com.naqqa.analytics.query.AnalyticsDtos.Realtime;
import com.naqqa.analytics.query.AnalyticsQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
public class RealtimeHub {

    private final AnalyticsQueryService queries;
    private final long timeoutMs;
    private final int maxEmitters;
    private final List<Client> clients = new CopyOnWriteArrayList<>();

    public RealtimeHub(AnalyticsQueryService queries, long timeoutMs, int maxEmitters) {
        this.queries = queries;
        this.timeoutMs = timeoutMs;
        this.maxEmitters = maxEmitters;
    }

    public SseEmitter subscribe(Set<String> companyIds) {
        if (clients.size() >= maxEmitters) {
            throw new AnalyticsException(503, "busy", "Too many realtime subscribers");
        }
        SseEmitter emitter = new SseEmitter(timeoutMs);
        Client client = new Client(emitter, companyIds == null ? null : new TreeSet<>(companyIds));
        clients.add(client);
        emitter.onCompletion(() -> clients.remove(client));
        emitter.onTimeout(() -> clients.remove(client));
        emitter.onError(e -> clients.remove(client));
        try {
            emitter.send(SseEmitter.event().name("realtime").data(queries.realtime(client.scope)));
        } catch (IOException | RuntimeException e) {
            clients.remove(client);
        }
        return emitter;
    }

    public int size() {
        return clients.size();
    }

    public void push() {
        if (clients.isEmpty()) {
            return;
        }
        Map<String, Realtime> snapshots = new HashMap<>();
        for (Client c : clients) {
            String key = String.valueOf(c.scope);
            Realtime data;
            try {
                data = snapshots.computeIfAbsent(key, k -> queries.realtime(c.scope));
            } catch (RuntimeException e) {
                log.debug("[analytics] realtime snapshot failed: {}", e.getMessage());
                continue;
            }
            try {
                c.emitter.send(SseEmitter.event().name("realtime").data(data));
            } catch (IOException | RuntimeException e) {
                clients.remove(c);
            }
        }
    }

    private record Client(SseEmitter emitter, Set<String> scope) {
    }
}
