package com.naqqa.elasticsearch.search.vectors.hnsw;

import com.naqqa.elasticsearch.search.vectors.NeighborQueue;
import com.naqqa.elasticsearch.search.vectors.RandomVectorScorer;
import com.naqqa.elasticsearch.search.vectors.RandomVectorScorerSupplier;

import java.util.BitSet;
import java.util.SplittableRandom;

public final class HnswGraphBuilder {

    private final RandomVectorScorerSupplier supplier;
    private final int m;
    private final int beamWidth;
    private final double ml;
    private final SplittableRandom random;
    private final HnswGraph graph;
    private final HnswGraphSearcher searcher;
    private final BitSet initializedNodes;
    private int[] scratchNodes = new int[64];
    private float[] scratchScores = new float[64];

    public HnswGraphBuilder(RandomVectorScorerSupplier supplier, HnswConfig config) {
        this(supplier, config, new HnswGraph(config.m(), supplier.maxOrd()), new BitSet());
    }

    private HnswGraphBuilder(RandomVectorScorerSupplier supplier, HnswConfig config, HnswGraph graph, BitSet initializedNodes) {
        this.supplier = supplier;
        this.m = config.m();
        this.beamWidth = config.efConstruction();
        this.ml = m == 1 ? 1 : 1 / Math.log(m);
        this.random = new SplittableRandom(config.seed());
        this.graph = graph;
        this.searcher = new HnswGraphSearcher(Math.max(1, supplier.maxOrd()));
        this.initializedNodes = initializedNodes;
    }

    public static HnswGraphBuilder fromExisting(RandomVectorScorerSupplier supplier, HnswConfig config, HnswGraph initializer, int[] oldToNewOrd) {
        HnswGraph graph = new HnswGraph(config.m(), supplier.maxOrd());
        BitSet initialized = new BitSet(supplier.maxOrd());
        if (initializer.maxConn() != config.m()) {
            throw new IllegalArgumentException("initializer graph m " + initializer.maxConn() + " != " + config.m());
        }
        for (int old = 0; old <= initializer.maxNodeId(); old++) {
            if (initializer.containsNode(old)) {
                int newOrd = oldToNewOrd[old];
                if (newOrd < 0) {
                    throw new IllegalArgumentException("initializer node " + old + " is not mapped");
                }
                graph.addNode(newOrd, initializer.nodeLevel(old));
                initialized.set(newOrd);
            }
        }
        for (int old = 0; old <= initializer.maxNodeId(); old++) {
            if (!initializer.containsNode(old)) {
                continue;
            }
            int newOrd = oldToNewOrd[old];
            for (int level = 0; level <= initializer.nodeLevel(old); level++) {
                NeighborArray src = initializer.neighbors(level, old);
                NeighborArray dst = graph.neighbors(level, newOrd);
                for (int i = 0; i < src.size(); i++) {
                    dst.append(oldToNewOrd[src.node(i)], src.score(i));
                }
            }
        }
        if (initializer.entryNode() >= 0) {
            graph.setEntryNode(oldToNewOrd[initializer.entryNode()], initializer.numLevels());
        }
        return new HnswGraphBuilder(supplier, config, graph, initialized);
    }

    public HnswGraph graph() {
        return graph;
    }

    public HnswGraph build() {
        int maxOrd = supplier.maxOrd();
        for (int node = 0; node < maxOrd; node++) {
            if (!initializedNodes.get(node)) {
                addGraphNode(node);
            }
        }
        return graph;
    }

    private int randomLevel() {
        double r;
        do {
            r = random.nextDouble();
        } while (r == 0.0);
        return (int) (-Math.log(r) * ml);
    }

    public void addGraphNode(int node) {
        int nodeLevel = randomLevel();
        if (graph.size() == 0 || graph.entryNode() < 0) {
            graph.addNode(node, nodeLevel);
            graph.setEntryNode(node, nodeLevel + 1);
            return;
        }
        RandomVectorScorer scorer = supplier.scorer(node);
        int curMaxLevel = graph.numLevels() - 1;
        int[] eps = new int[] {graph.entryNode()};
        NeighborQueue[] perLevel = new NeighborQueue[nodeLevel + 1];
        for (int level = curMaxLevel; level > nodeLevel; level--) {
            NeighborQueue candidates = searcher.searchLevel(scorer, 1, level, eps, graph);
            eps = new int[] {candidates.topNode()};
        }
        for (int level = Math.min(nodeLevel, curMaxLevel); level >= 0; level--) {
            NeighborQueue candidates = searcher.searchLevel(scorer, beamWidth, level, eps, graph);
            eps = candidates.nodes();
            perLevel[level] = candidates;
        }
        graph.addNode(node, nodeLevel);
        for (int level = Math.min(nodeLevel, curMaxLevel); level >= 0; level--) {
            addDiverseNeighbors(level, node, perLevel[level]);
        }
        if (nodeLevel > curMaxLevel) {
            graph.setEntryNode(node, nodeLevel + 1);
        }
    }

    private void addDiverseNeighbors(int level, int node, NeighborQueue candidates) {
        NeighborArray neighbors = graph.neighbors(level, node);
        int maxConnOnLevel = graph.maxConnOnLevel(level);
        int count = candidates.size();
        if (scratchNodes.length < count) {
            scratchNodes = new int[count];
            scratchScores = new float[count];
        }
        for (int i = count - 1; i >= 0; i--) {
            scratchScores[i] = candidates.topScore();
            scratchNodes[i] = candidates.pop();
        }
        for (int i = 0; i < count && neighbors.size() < maxConnOnLevel; i++) {
            int cNode = scratchNodes[i];
            float cScore = scratchScores[i];
            if (cNode == node) {
                continue;
            }
            if (diversityCheck(cNode, cScore, neighbors)) {
                neighbors.addInOrder(cNode, cScore);
            }
        }
        int size = neighbors.size();
        for (int i = 0; i < size; i++) {
            int nbr = neighbors.node(i);
            NeighborArray nbrsOfNbr = graph.neighbors(level, nbr);
            if (nbrsOfNbr.contains(node)) {
                continue;
            }
            nbrsOfNbr.insertSorted(node, neighbors.score(i));
            if (nbrsOfNbr.size() > maxConnOnLevel) {
                nbrsOfNbr.removeIndex(findWorstNonDiverse(nbrsOfNbr));
            }
        }
    }

    private boolean diversityCheck(int candidate, float score, NeighborArray neighbors) {
        RandomVectorScorer scorer = supplier.scorer(candidate);
        for (int i = 0; i < neighbors.size(); i++) {
            if (scorer.score(neighbors.node(i)) >= score) {
                return false;
            }
        }
        return true;
    }

    private int findWorstNonDiverse(NeighborArray neighbors) {
        for (int i = neighbors.size() - 1; i > 0; i--) {
            int cNode = neighbors.node(i);
            float minAccepted = neighbors.score(i);
            RandomVectorScorer scorer = supplier.scorer(cNode);
            for (int j = 0; j < i; j++) {
                if (scorer.score(neighbors.node(j)) >= minAccepted) {
                    return i;
                }
            }
        }
        return neighbors.size() - 1;
    }
}
