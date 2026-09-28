package com.naqqa.elasticsearch.index.engine;

import com.naqqa.elasticsearch.test.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class HeapBoundedIndexingTest {

    @Test
    public void indexes500kDocsUnderConstrainedHeapWithoutOom() throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");
        Path workDir = Files.createTempDirectory("oom-fix-heap-bounded");

        ProcessBuilder pb = new ProcessBuilder(
            javaBin, "-Xmx256m", "--add-modules", "jdk.incubator.vector",
            "-cp", classpath,
            "com.naqqa.elasticsearch.index.engine.IndexingMemoryOomHarness",
            workDir.toString(), "500000", String.valueOf(24L * 1024 * 1024));
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        boolean finished = process.waitFor(240, TimeUnit.SECONDS);
        assertTrue(finished, "harness process should finish within timeout, output so far:\n" + output);
        int exit = process.exitValue();
        assertEquals(0, exit, "harness process should exit cleanly without OutOfMemoryError, output:\n" + output);
        assertTrue(output.toString().contains("DONE 500000"), "harness should report completion, output:\n" + output);
    }
}
