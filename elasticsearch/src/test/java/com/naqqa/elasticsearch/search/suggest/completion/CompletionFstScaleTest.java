package com.naqqa.elasticsearch.search.suggest.completion;

import com.naqqa.elasticsearch.test.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class CompletionFstScaleTest {

    @Test
    public void buildsCompletionStructureOver1_4MillionTitlesUnderConstrainedHeapWithoutOom() throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");

        ProcessBuilder pb = new ProcessBuilder(
            javaBin, "-Xmx512m", "--add-modules", "jdk.incubator.vector",
            "-cp", classpath,
            "com.naqqa.elasticsearch.search.suggest.completion.CompletionScaleHarness",
            "1400000");
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
        assertTrue(output.toString().contains("DONE 1400000"), "harness should report completion, output:\n" + output);
    }
}
