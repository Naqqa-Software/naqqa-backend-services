package com.naqqa.outreach.service;

import com.naqqa.outreach.config.OutreachProperties;
import com.naqqa.outreach.entity.OutreachLogEntity;
import com.naqqa.outreach.repository.OutreachLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Persists an outreach console log line to two places: a per-profile daily {@code .txt} file
 * (<code>{logDir}/{profile}/{yyyy-MM-dd}.txt</code>) and the {@code outreach_logs} Mongo collection.
 * Fed by {@link com.naqqa.outreach.logging.OutreachLogAppender}. It must NOT use SLF4J itself —
 * its own logger sits under {@code com.naqqa.outreach}, so logging here would loop back through the
 * appender; failures are swallowed to {@code System.err}.
 */
@Service
@RequiredArgsConstructor
public class OutreachLogService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);

    private final OutreachLogRepository repo;
    private final OutreachProperties props;

    public void record(String profileKey, String level, String logger, String message, Instant ts) {
        String key = (profileKey == null || profileKey.isBlank()) ? "system" : profileKey;
        String day = DAY.format(ts);
        appendFile(key, day, level, message, ts);
        saveDoc(key, day, level, logger, message, ts);
    }

    /**
     * Appends one line to a DEDICATED per-profile daily file (e.g. {@code {logDir}/{profile}/
     * sent-{day}.txt}) — a clean, analyzable stream separate from the noisy operational log. Used
     * for the email-sending log and the replies log. Does NOT touch Mongo; never uses SLF4J.
     */
    public void recordToStream(String profileKey, String stream, String message) {
        String key = (profileKey == null || profileKey.isBlank()) ? "system" : profileKey;
        Instant ts = Instant.now();
        String day = DAY.format(ts);
        try {
            Path dir = Paths.get(props.getLogDir(), key);
            Files.createDirectories(dir);
            Path file = dir.resolve(stream + "-" + day + ".txt");
            String line = "[" + TIME.format(ts) + "] " + message + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.err.println("outreach " + stream + " log write failed: " + e.getMessage());
        }
    }

    private void appendFile(String key, String day, String level, String message, Instant ts) {
        try {
            Path dir = Paths.get(props.getLogDir(), key);
            Files.createDirectories(dir);
            Path file = dir.resolve(day + ".txt");
            String line = "[" + TIME.format(ts) + "] " + level + " " + message + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.err.println("outreach log file write failed: " + e.getMessage());
        }
    }

    private void saveDoc(String key, String day, String level, String logger, String message, Instant ts) {
        try {
            OutreachLogEntity e = new OutreachLogEntity();
            e.setProfileKey(key);
            e.setDay(day);
            e.setLevel(level);
            e.setLogger(logger);
            e.setMessage(message);
            e.setCreatedAt(ts);
            repo.save(e);
        } catch (Exception e) {
            System.err.println("outreach log db write failed: " + e.getMessage());
        }
    }
}
