package com.naqqa.elasticsearch.monitor.version;

import com.naqqa.elasticsearch.test.Test;
import java.util.Optional;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class VersionCompatibilityTest {

    @Test
    public void sameMajorVersionIsReadWrite() {
        assertEquals(VersionCompatibility.IndexCompatibility.READ_WRITE,
                VersionCompatibility.indexCompatibility(9, 9));
    }

    @Test
    public void previousMajorVersionIsReadOnly() {
        assertEquals(VersionCompatibility.IndexCompatibility.READ_ONLY,
                VersionCompatibility.indexCompatibility(9, 8));
    }

    @Test
    public void twoMajorsBackIsUnsupported() {
        assertEquals(VersionCompatibility.IndexCompatibility.UNSUPPORTED,
                VersionCompatibility.indexCompatibility(9, 7));
    }

    @Test
    public void futureMajorVersionIsUnsupported() {
        assertEquals(VersionCompatibility.IndexCompatibility.UNSUPPORTED,
                VersionCompatibility.indexCompatibility(9, 10));
    }

    @Test
    public void negotiatesOverlappingTransportVersionRanges() {
        TransportVersionRange local = new TransportVersionRange(5, 10);
        TransportVersionRange remote = new TransportVersionRange(8, 12);

        Optional<Integer> negotiated = VersionCompatibility.negotiate(local, remote);

        assertTrue(negotiated.isPresent());
        assertEquals(10, (int) negotiated.get());
    }

    @Test
    public void negotiationFailsWhenRangesDoNotOverlap() {
        TransportVersionRange local = new TransportVersionRange(1, 3);
        TransportVersionRange remote = new TransportVersionRange(5, 8);

        Optional<Integer> negotiated = VersionCompatibility.negotiate(local, remote);

        assertFalse(negotiated.isPresent());
    }
}
