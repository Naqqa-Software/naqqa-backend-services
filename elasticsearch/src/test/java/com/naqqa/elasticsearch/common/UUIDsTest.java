package com.naqqa.elasticsearch.common;

import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.HashSet;
import java.util.Set;

public class UUIDsTest {

    @Test
    public void testRandomUuidsAreUniqueAndUrlSafe() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String uuid = UUIDs.randomBase64UUID();
            Assert.assertTrue(seen.add(uuid), "duplicate uuid generated: " + uuid);
            Assert.assertFalse(uuid.contains("+"));
            Assert.assertFalse(uuid.contains("/"));
        }
    }

    @Test
    public void testTimeBasedUuidsAreUniqueAndSortableFriendly() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String uuid = UUIDs.base64TimeBasedUUID();
            Assert.assertTrue(seen.add(uuid), "duplicate time based uuid: " + uuid);
        }
    }
}
