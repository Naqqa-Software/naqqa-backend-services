package com.naqqa.auth.sso.oidc;

import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.sso.SsoException;
import com.naqqa.sso.oauth.SafeHttp;
import com.naqqa.sso.oidc.BackchannelLogout;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class BackchannelLogoutDispatcher implements AutoCloseable {

    private final SafeHttp http;
    private final SsoAudit audit;
    private final int maxAttempts;
    private final Duration backoff;
    private final ScheduledExecutorService executor;
    private final AtomicInteger pending = new AtomicInteger();

    public BackchannelLogoutDispatcher(SsoProperties.Backchannel config, SsoAudit audit) {
        SafeHttp.Options base = config.isTestMode() ? SafeHttp.Options.forTests() : SafeHttp.Options.production();
        Duration timeout = config.getTimeout() == null ? SafeHttp.TOTAL_TIMEOUT : config.getTimeout();
        this.http = new SafeHttp(base.withTotalTimeout(timeout).withMaxBytes(16 * 1024));
        this.audit = audit;
        this.maxAttempts = Math.max(1, Math.min(10, config.getMaxAttempts()));
        this.backoff = config.getBackoff() == null ? Duration.ofSeconds(2) : config.getBackoff();
        AtomicInteger seq = new AtomicInteger();
        this.executor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "sso-backchannel-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    public void dispatch(List<BackchannelLogout> logouts) {
        if (logouts == null) {
            return;
        }
        for (BackchannelLogout l : logouts) {
            pending.incrementAndGet();
            executor.execute(() -> attempt(l, 1));
        }
    }

    public int pending() {
        return pending.get();
    }

    private void attempt(BackchannelLogout logout, int attempt) {
        String outcome;
        try {
            SafeHttp.Response r = http.postForm(logout.uri(), Map.of("logout_token", logout.logoutToken()),
                    Map.of("Cache-Control", "no-store"), List.of());
            outcome = r.ok() ? "success" : "status_" + r.status();
        } catch (SsoException e) {
            outcome = e.code();
        } catch (RuntimeException e) {
            outcome = "error";
        }
        if ("success".equals(outcome)) {
            finish(logout, "success", attempt, null);
            return;
        }
        if (attempt >= maxAttempts || executor.isShutdown()) {
            finish(logout, "failure", attempt, outcome);
            return;
        }
        long delay = backoff.toMillis() * (1L << Math.min(attempt - 1, 6));
        try {
            executor.schedule(() -> attempt(logout, attempt + 1), delay, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            finish(logout, "failure", attempt, "rejected");
        }
    }

    private void finish(BackchannelLogout logout, String outcome, int attempt, String reason) {
        try {
            audit.record("oidc.backchannel_logout", null, logout.clientId(), outcome, reason == null
                    ? Map.of("attempts", Integer.toString(attempt))
                    : Map.of("attempts", Integer.toString(attempt), "reason", reason), null);
        } catch (RuntimeException e) {
            log.warn("backchannel audit failed for client {}", logout.clientId());
        } finally {
            pending.decrementAndGet();
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
