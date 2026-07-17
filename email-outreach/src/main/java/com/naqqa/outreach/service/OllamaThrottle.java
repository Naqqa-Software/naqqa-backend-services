package com.naqqa.outreach.service;

import com.naqqa.outreach.config.OutreachProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Global Ollama gate — faithful port of the script's shared chat-lock + 20 s cooldown. Only one
 * generation runs at a time across BOTH profiles (single permit), and each caller waits the
 * configured cooldown after its call. Prevents the two concurrent workers from double-loading the
 * local 20B model.
 */
@Component
@RequiredArgsConstructor
public class OllamaThrottle {

    private final OutreachProperties props;
    private final Semaphore lock = new Semaphore(1, true);

    public <T> T execute(Supplier<T> call) {
        // Chat-lock off → let Ollama serialize requests itself; a stuck call never blocks others.
        if (!props.isOllamaChatLock()) {
            return call.get();
        }
        try {
            lock.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted acquiring Ollama lock", e);
        }
        try {
            return call.get();
        } finally {
            lock.release();
            long cooldown = props.getOllamaCooldownMs();
            if (cooldown > 0) {
                try {
                    Thread.sleep(cooldown);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
