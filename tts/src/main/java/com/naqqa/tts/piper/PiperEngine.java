package com.naqqa.tts.piper;

import com.naqqa.tts.config.TtsProperties;
import com.naqqa.tts.core.TtsEngine;
import com.naqqa.tts.core.TtsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

public class PiperEngine implements TtsEngine {

    private static final Logger log = LoggerFactory.getLogger(PiperEngine.class);

    private final TtsProperties properties;
    private final PiperInstaller installer;
    private final Map<String, PiperProcess> processes = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Map<String, Path> models = new ConcurrentHashMap<>();
    private final AtomicBoolean preparing = new AtomicBoolean();
    private final ExecutorService setup = Executors.newSingleThreadExecutor(r -> daemon(r, "piper-setup"));
    private final ScheduledExecutorService janitor = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "piper-idle"));
    private volatile Path binary;
    private volatile boolean ready;

    public PiperEngine(TtsProperties properties, PiperInstaller installer) {
        this.properties = properties;
        this.installer = installer;
        janitor.scheduleWithFixedDelay(this::unloadIdle, 1, 1, TimeUnit.MINUTES);
    }

    @Override
    public boolean ready() {
        return ready;
    }

    @Override
    public void prepare() {
        if (ready || !preparing.compareAndSet(false, true)) {
            return;
        }
        setup.submit(() -> {
            try {
                binary = installer.binary();
                for (Map.Entry<String, TtsProperties.Voice> e : properties.getVoices().entrySet()) {
                    models.put(e.getKey(), installer.model(e.getValue()));
                }
                ready = true;
                log.info("TTS ready with voices {}", properties.getVoices().keySet());
            } catch (Exception e) {
                log.warn("TTS setup failed: {}", e.getMessage());
            } finally {
                preparing.set(false);
            }
        });
    }

    @Override
    public Path synthesize(String text, String lang, TtsProperties.Voice voice, Path output) {
        Path model = models.get(lang);
        if (!ready || model == null || binary == null) {
            prepare();
            throw new TtsException(TtsException.Reason.UNAVAILABLE, "Voice for " + lang + " is not ready");
        }
        ReentrantLock lock = locks.computeIfAbsent(lang, k -> new ReentrantLock(true));
        boolean locked;
        try {
            locked = lock.tryLock(properties.getAcquireTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TtsException(TtsException.Reason.BUSY, "Interrupted while waiting for the voice");
        }
        if (!locked) {
            throw new TtsException(TtsException.Reason.BUSY, "Voice " + lang + " is busy");
        }
        try {
            PiperProcess process = processes.computeIfAbsent(lang, k -> create(model, voice));
            return process.synthesize(text, output, properties.getSynthTimeoutMs());
        } catch (TimeoutException e) {
            throw new TtsException(TtsException.Reason.FAILED, e.getMessage(), e);
        } catch (IOException e) {
            PiperProcess broken = processes.remove(lang);
            if (broken != null) {
                broken.destroy();
            }
            throw new TtsException(TtsException.Reason.FAILED, "Piper failed: " + e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    private PiperProcess create(Path model, TtsProperties.Voice voice) {
        List<String> command = new ArrayList<>(properties.getPiper().getCommandPrefix().stream().filter(s -> s != null && !s.isBlank()).toList());
        command.add(binary.toString());
        command.add("--model");
        command.add(model.toString());
        command.add("--json-input");
        command.add("--sentence_silence");
        command.add(String.format(Locale.ROOT, "%.2f", properties.getPiper().getSentenceSilence()));
        if (voice.getLengthScale() != null) {
            command.add("--length_scale");
            command.add(String.format(Locale.ROOT, "%.2f", voice.getLengthScale()));
        }
        Path dir = binary.getParent();
        String ld = System.getenv("LD_LIBRARY_PATH");
        Map<String, String> env = Map.of("LD_LIBRARY_PATH", ld == null || ld.isBlank() ? dir.toString() : dir + ":" + ld);
        return new PiperProcess(command, dir, env, voice.getSpeaker());
    }

    private void unloadIdle() {
        long idleMs = TimeUnit.MINUTES.toMillis(Math.max(1, properties.getIdleUnloadMinutes()));
        long now = System.currentTimeMillis();
        processes.forEach((lang, process) -> {
            ReentrantLock lock = locks.computeIfAbsent(lang, k -> new ReentrantLock(true));
            if (now - process.lastUsed() > idleMs && lock.tryLock()) {
                try {
                    if (process.running()) {
                        process.destroy();
                        log.info("Piper voice {} unloaded after being idle", lang);
                    }
                } finally {
                    lock.unlock();
                }
            }
        });
    }

    @Override
    public void shutdown() {
        janitor.shutdownNow();
        setup.shutdownNow();
        processes.values().forEach(PiperProcess::destroy);
        processes.clear();
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
