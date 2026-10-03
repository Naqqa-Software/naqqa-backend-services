package com.naqqa.tts.piper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class PiperProcess implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PiperProcess.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<String> command;
    private final Path workDir;
    private final Map<String, String> env;
    private final Integer speaker;
    private Process process;
    private BufferedWriter stdin;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private volatile long lastUsed = System.currentTimeMillis();

    PiperProcess(List<String> command, Path workDir, Map<String, String> env, Integer speaker) {
        this.command = command;
        this.workDir = workDir;
        this.env = env;
        this.speaker = speaker;
    }

    synchronized Path synthesize(String text, Path output, long timeoutMs) throws IOException, TimeoutException {
        lastUsed = System.currentTimeMillis();
        ensureStarted();
        lines.clear();
        Files.deleteIfExists(output);
        ObjectNode request = JSON.createObjectNode();
        request.put("text", text);
        request.put("output_file", output.toAbsolutePath().toString());
        if (speaker != null) {
            request.put("speaker_id", speaker);
        }
        try {
            stdin.write(JSON.writeValueAsString(request));
            stdin.write('\n');
            stdin.flush();
        } catch (IOException e) {
            destroy();
            throw e;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            while (true) {
                long left = deadline - System.currentTimeMillis();
                String line = left > 0 ? lines.poll(left, TimeUnit.MILLISECONDS) : null;
                if (line == null) {
                    destroy();
                    throw new TimeoutException("Piper did not answer within " + timeoutMs + " ms");
                }
                if (line.isBlank()) {
                    continue;
                }
                break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroy();
            throw new IOException("Interrupted while waiting for Piper", e);
        }
        lastUsed = System.currentTimeMillis();
        if (!Files.isRegularFile(output) || Files.size(output) <= 44) {
            throw new IOException("Piper produced no audio");
        }
        return output;
    }

    long lastUsed() {
        return lastUsed;
    }

    synchronized boolean running() {
        return process != null && process.isAlive();
    }

    private void ensureStarted() throws IOException {
        if (process != null && process.isAlive()) {
            return;
        }
        destroy();
        ProcessBuilder builder = new ProcessBuilder(command).directory(workDir.toFile());
        builder.environment().putAll(env);
        process = builder.start();
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        Process current = process;
        Thread out = new Thread(() -> pump(current, true), "piper-out");
        Thread err = new Thread(() -> pump(current, false), "piper-err");
        out.setDaemon(true);
        err.setDaemon(true);
        out.start();
        err.start();
        log.info("Piper started: {}", String.join(" ", command));
    }

    private void pump(Process p, boolean stdout) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                stdout ? p.getInputStream() : p.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (stdout) {
                    lines.offer(line);
                } else if (line.contains("[error]") || line.contains("[critical]")) {
                    log.warn("piper: {}", line);
                } else {
                    log.debug("piper: {}", line);
                }
            }
        } catch (IOException ignored) {
            log.debug("Piper stream closed");
        }
    }

    synchronized void destroy() {
        if (process == null) {
            return;
        }
        try {
            if (stdin != null) {
                stdin.close();
            }
        } catch (IOException ignored) {
            log.debug("Piper stdin already closed");
        }
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        process = null;
        stdin = null;
    }

    @Override
    public void close() {
        destroy();
    }
}
