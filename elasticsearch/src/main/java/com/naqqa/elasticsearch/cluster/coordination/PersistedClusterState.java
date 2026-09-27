package com.naqqa.elasticsearch.cluster.coordination;

import com.naqqa.elasticsearch.cluster.state.ClusterState;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class PersistedClusterState {

    private final long currentTerm;
    private final ClusterState lastAcceptedState;

    public PersistedClusterState(long currentTerm, ClusterState lastAcceptedState) {
        this.currentTerm = currentTerm;
        this.lastAcceptedState = lastAcceptedState;
    }

    public long getCurrentTerm() {
        return currentTerm;
    }

    public ClusterState getLastAcceptedState() {
        return lastAcceptedState;
    }

    public static void save(Path dataDir, PersistedClusterState state) {
        try {
            Files.createDirectories(dataDir);
            Path target = dataDir.resolve("_state.dat");
            Path tmp = dataDir.resolve("_state.dat.tmp");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeLong(state.currentTerm);
            state.lastAcceptedState.writeTo(out);
            Files.write(tmp, bytes.toByteArray());
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static PersistedClusterState load(Path dataDir, String clusterName) {
        Path target = dataDir.resolve("_state.dat");
        if (!Files.exists(target)) {
            return new PersistedClusterState(0L, ClusterState.builder(clusterName).build());
        }
        try {
            byte[] bytes = Files.readAllBytes(target);
            DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(bytes));
            long term = in.readLong();
            ClusterState state = ClusterState.readFrom(in);
            return new PersistedClusterState(term, state);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
