package com.naqqa.filestorage;

import com.naqqa.filestorage.config.FileStorageProperties;
import com.naqqa.filestorage.service.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PathSanitizationTest {

    private static class Probe extends FileStorageService {
        Probe() { super(null, null, new FileStorageProperties()); }
        String dir(String s) { return sanitizeDirectory(s); }
        String file(String s) { return sanitizeFileName(s); }
    }

    private Probe probe;

    @BeforeEach
    void setUp() {
        probe = new Probe();
    }

    @Test
    void rejectsTraversal() {
        assertThrows(IllegalArgumentException.class, () -> probe.dir("../../etc"));
        assertThrows(IllegalArgumentException.class, () -> probe.dir("public/../private"));
        assertThrows(IllegalArgumentException.class, () -> probe.dir("a//b"));
    }

    @Test
    void rejectsControlCharacters() {
        assertThrows(IllegalArgumentException.class, () -> probe.dir("apple\nbanana"));
        assertThrows(IllegalArgumentException.class, () -> probe.dir("nul\u0000byte"));
    }

    @Test
    void normalisesSlashesAndWhitespace() {
        assertEquals("courses/videos", probe.dir("/courses/videos/"));
        assertEquals("courses/videos", probe.dir("  courses/videos  "));
        assertEquals("courses/videos", probe.dir("\\courses\\videos"));
    }

    @Test
    void blankDirectoryFallsBackToGeneral() {
        assertEquals("general", probe.dir(null));
        assertEquals("general", probe.dir("   "));
    }

    @Test
    void normalisationCannotBeUsedToSmuggleThePublicPrefix() {
        assertEquals("public/x", probe.dir("  public/x  "));
        assertEquals("public/x", probe.dir("/public/x"));
    }

    @Test
    void stripsPathComponentsFromFilenames() {
        assertEquals("evil.sh", probe.file("../../evil.sh"));
        assertEquals("evil.sh", probe.file("/tmp/evil.sh"));
        assertEquals("evil.sh", probe.file("C:\\windows\\evil.sh"));
        assertEquals("file", probe.file(".."));
        assertEquals("file", probe.file(null));
    }
}
