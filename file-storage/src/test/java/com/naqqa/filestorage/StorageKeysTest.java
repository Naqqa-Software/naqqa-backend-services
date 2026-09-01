package com.naqqa.filestorage;

import com.naqqa.filestorage.support.StorageKeys;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StorageKeysTest {

    @Test
    void keepsExtensionAndInsertsTokenBeforeIt() {
        String out = StorageKeys.uniquify("lesson.mp4");
        assertTrue(out.startsWith("lesson_"), out);
        assertTrue(out.endsWith(".mp4"), out);
        assertNotEquals("lesson.mp4", out);
    }

    @Test
    void identicalNamesNeverCollide() {
        // The whole point: the same filename uploaded repeatedly must not produce the same key.
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5_000; i++) {
            assertTrue(seen.add(StorageKeys.uniquify("video.mp4")), "collision at " + i);
        }
    }

    @Test
    void handlesNamesWithoutUsableExtension() {
        assertTrue(StorageKeys.uniquify("README").startsWith("README_"));
        assertTrue(StorageKeys.uniquify("archive.").startsWith("archive._"));
        assertTrue(StorageKeys.uniquify(".gitignore").startsWith(".gitignore_"));
    }

    @Test
    void handlesNullAndBlank() {
        assertTrue(StorageKeys.uniquify(null).startsWith("file_"));
        assertTrue(StorageKeys.uniquify("   ").startsWith("file_"));
    }
}
