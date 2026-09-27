package com.naqqa.elasticsearch.search.vectors.hnsw;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

public final class HnswGraph {

    public static final int MAGIC = 0x484E5357;
    public static final int VERSION = 1;

    private final int maxConn;
    private NeighborArray[][] nodes;
    private int size;
    private int maxNodeId = -1;
    private int numLevels;
    private int entryNode = -1;
    private int[] levelCounts = new int[4];

    public HnswGraph(int maxConn, int capacity) {
        if (maxConn <= 0) {
            throw new IllegalArgumentException("maxConn must be positive");
        }
        this.maxConn = maxConn;
        this.nodes = new NeighborArray[Math.max(1, capacity)][];
    }

    public int maxConn() {
        return maxConn;
    }

    public int maxConnOnLevel(int level) {
        return level == 0 ? maxConn * 2 : maxConn;
    }

    public int size() {
        return size;
    }

    public int maxNodeId() {
        return maxNodeId;
    }

    public int numLevels() {
        return numLevels;
    }

    public int entryNode() {
        return entryNode;
    }

    public boolean containsNode(int node) {
        return node >= 0 && node < nodes.length && nodes[node] != null;
    }

    public int nodeLevel(int node) {
        if (!containsNode(node)) {
            return -1;
        }
        return nodes[node].length - 1;
    }

    public void addNode(int node, int level) {
        if (node < 0) {
            throw new IllegalArgumentException("node must be >= 0");
        }
        if (node >= nodes.length) {
            nodes = Arrays.copyOf(nodes, Math.max(node + 1, nodes.length + (nodes.length >> 1) + 1));
        }
        if (nodes[node] != null) {
            throw new IllegalStateException("node " + node + " already exists");
        }
        NeighborArray[] levels = new NeighborArray[level + 1];
        for (int l = 0; l <= level; l++) {
            levels[l] = new NeighborArray(maxConnOnLevel(l) + 1);
        }
        nodes[node] = levels;
        size++;
        maxNodeId = Math.max(maxNodeId, node);
        if (level >= levelCounts.length) {
            levelCounts = Arrays.copyOf(levelCounts, level + 4);
        }
        for (int l = 0; l <= level; l++) {
            levelCounts[l]++;
        }
        if (entryNode == -1) {
            entryNode = node;
            numLevels = level + 1;
        }
    }

    public void setEntryNode(int node, int levels) {
        this.entryNode = node;
        this.numLevels = levels;
    }

    public NeighborArray neighbors(int level, int node) {
        return nodes[node][level];
    }

    public int nodeCountOnLevel(int level) {
        return level < levelCounts.length ? levelCounts[level] : 0;
    }

    public int[] nodesOnLevel(int level) {
        int[] out = new int[nodeCountOnLevel(level)];
        int n = 0;
        for (int node = 0; node <= maxNodeId; node++) {
            NeighborArray[] levels = nodes[node];
            if (levels != null && levels.length > level) {
                out[n++] = node;
            }
        }
        return out;
    }

    public long ramBytesUsed() {
        long bytes = 64L + nodes.length * 8L;
        for (int node = 0; node <= maxNodeId; node++) {
            NeighborArray[] levels = nodes[node];
            if (levels != null) {
                for (NeighborArray a : levels) {
                    bytes += 32L + a.capacity() * 8L;
                }
            }
        }
        return bytes;
    }

    public void writeTo(DataOutput out) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(maxConn);
        out.writeInt(maxNodeId + 1);
        out.writeInt(numLevels);
        out.writeInt(entryNode);
        for (int node = 0; node <= maxNodeId; node++) {
            NeighborArray[] levels = nodes[node];
            out.writeInt(levels == null ? -1 : levels.length - 1);
        }
        for (int node = 0; node <= maxNodeId; node++) {
            NeighborArray[] levels = nodes[node];
            if (levels == null) {
                continue;
            }
            for (NeighborArray neighbors : levels) {
                out.writeInt(neighbors.size());
                for (int i = 0; i < neighbors.size(); i++) {
                    out.writeInt(neighbors.node(i));
                    out.writeFloat(neighbors.score(i));
                }
            }
        }
    }

    public static HnswGraph readFrom(DataInput in) throws IOException {
        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new IOException("invalid HNSW graph magic: 0x" + Integer.toHexString(magic));
        }
        int version = in.readInt();
        if (version != VERSION) {
            throw new IOException("unsupported HNSW graph version: " + version);
        }
        int maxConn = in.readInt();
        int nodeSlots = in.readInt();
        int numLevels = in.readInt();
        int entryNode = in.readInt();
        if (maxConn <= 0 || nodeSlots < 0 || numLevels < 0) {
            throw new IOException("corrupt HNSW graph header");
        }
        HnswGraph graph = new HnswGraph(maxConn, nodeSlots);
        int[] nodeLevels = new int[nodeSlots];
        for (int node = 0; node < nodeSlots; node++) {
            nodeLevels[node] = in.readInt();
            if (nodeLevels[node] >= numLevels) {
                throw new IOException("corrupt HNSW graph: node level " + nodeLevels[node] + " >= " + numLevels);
            }
        }
        for (int node = 0; node < nodeSlots; node++) {
            if (nodeLevels[node] >= 0) {
                graph.addNode(node, nodeLevels[node]);
            }
        }
        for (int node = 0; node < nodeSlots; node++) {
            for (int level = 0; level <= nodeLevels[node]; level++) {
                NeighborArray neighbors = graph.neighbors(level, node);
                int count = in.readInt();
                if (count < 0 || count > neighbors.capacity()) {
                    throw new IOException("corrupt HNSW graph: neighbor count " + count);
                }
                for (int i = 0; i < count; i++) {
                    int nbr = in.readInt();
                    float score = in.readFloat();
                    if (nbr < 0 || nbr >= nodeSlots) {
                        throw new IOException("corrupt HNSW graph: neighbor " + nbr);
                    }
                    neighbors.append(nbr, score);
                }
            }
        }
        if (entryNode >= nodeSlots || (nodeSlots > 0 && entryNode >= 0 && nodeLevels[entryNode] < 0)) {
            throw new IOException("corrupt HNSW graph: entry node " + entryNode);
        }
        graph.setEntryNode(entryNode, numLevels);
        return graph;
    }
}
