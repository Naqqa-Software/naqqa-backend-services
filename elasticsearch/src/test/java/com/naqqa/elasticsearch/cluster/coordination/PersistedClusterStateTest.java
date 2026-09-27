package com.naqqa.elasticsearch.cluster.coordination;

import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNodeRole;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNodes;
import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.cluster.state.CoordinationMetadata;
import com.naqqa.elasticsearch.cluster.state.Metadata;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class PersistedClusterStateTest {

    @Test
    public void testSaveAndLoadRoundTripsTermAndLastAcceptedState() throws IOException {
        Path dir = Files.createTempDirectory("coordination-state-test");
        try {
            DiscoveryNode node = new DiscoveryNode("node-a", "node-a", "host:9300", Map.of(),
                EnumSet.of(DiscoveryNodeRole.MASTER), 1L);
            DiscoveryNodes nodes = DiscoveryNodes.builder().add(node).masterNodeId(node.getId()).build();
            VotingConfiguration config = new VotingConfiguration(Set.of(node.getId()));
            CoordinationMetadata coordinationMetadata = new CoordinationMetadata(7L, config, config, Set.of());
            Metadata metadata = Metadata.EMPTY.toBuilder().coordinationMetadata(coordinationMetadata).build();
            ClusterState state = ClusterState.builder("test-cluster").nodes(nodes).metadata(metadata).version(42L).build();

            PersistedClusterState.save(dir, new PersistedClusterState(7L, state));
            PersistedClusterState loaded = PersistedClusterState.load(dir, "test-cluster");

            Assert.assertEquals(7L, loaded.getCurrentTerm());
            Assert.assertEquals(42L, loaded.getLastAcceptedState().getVersion());
            Assert.assertEquals(7L, loaded.getLastAcceptedState().getMetadata().coordinationMetadata().getTerm());
            Assert.assertEquals(node.getId(), loaded.getLastAcceptedState().getNodes().getMasterNodeId());
            Assert.assertTrue(loaded.getLastAcceptedState().getMetadata().coordinationMetadata()
                .getLastAcceptedConfiguration().contains(node.getId()));
        } finally {
            Files.walk(dir).sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    @Test
    public void testCoordinationStateReloadsPersistedTermAcrossRestart() throws IOException {
        Path dir = Files.createTempDirectory("coordination-state-test-2");
        try {
            DiscoveryNode node = new DiscoveryNode("node-b", "node-b", "host:9301", Map.of(),
                EnumSet.of(DiscoveryNodeRole.MASTER), 1L);
            CoordinationState first = new CoordinationState(node, dir);
            Join join = first.handleStartJoin(new StartJoinRequest(node, 5L));
            Assert.assertEquals(5L, join.term());
            Assert.assertEquals(5L, first.getCurrentTerm());

            CoordinationState reloaded = new CoordinationState(node, dir);
            Assert.assertEquals(5L, reloaded.getCurrentTerm());
        } finally {
            Files.walk(dir).sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
