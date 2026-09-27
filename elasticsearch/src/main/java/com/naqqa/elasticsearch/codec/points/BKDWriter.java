package com.naqqa.elasticsearch.codec.points;

import com.naqqa.elasticsearch.codec.NumericUtils;
import com.naqqa.elasticsearch.store.BytesDataOutput;
import com.naqqa.elasticsearch.store.CodecUtil;
import com.naqqa.elasticsearch.store.IndexOutput;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class BKDWriter {

    public static final String CODEC_NAME = "NaqqaBKD";
    public static final int VERSION_START = 1;
    public static final int VERSION_CURRENT = 1;
    public static final int DEFAULT_MAX_POINTS_IN_LEAF = 512;

    private final int numDims;
    private final int bytesPerDim;
    private final int maxPointsInLeaf;
    private final List<Point> points = new ArrayList<>();

    private static final class Point {
        final byte[] packedValue;
        final int docId;

        Point(byte[] packedValue, int docId) {
            this.packedValue = packedValue;
            this.docId = docId;
        }
    }

    public BKDWriter(int numDims, int bytesPerDim, int maxPointsInLeaf) {
        if (numDims < 1 || numDims > 8) {
            throw new IllegalArgumentException("numDims must be in [1,8], got " + numDims);
        }
        this.numDims = numDims;
        this.bytesPerDim = bytesPerDim;
        this.maxPointsInLeaf = maxPointsInLeaf;
    }

    public void add(byte[] packedValue, int docId) {
        if (packedValue.length != numDims * bytesPerDim) {
            throw new IllegalArgumentException("packedValue length mismatch");
        }
        points.add(new Point(packedValue.clone(), docId));
    }

    public void finish(IndexOutput out) throws IOException {
        CodecUtil.writeHeader(out, CODEC_NAME, VERSION_CURRENT);
        out.writeVInt(numDims);
        out.writeVInt(bytesPerDim);
        out.writeVInt(points.size());
        byte[][] globalMin = new byte[numDims][];
        byte[][] globalMax = new byte[numDims][];
        computeBounds(points, globalMin, globalMax);
        for (int d = 0; d < numDims; d++) {
            out.writeBytes(globalMin[d], 0, bytesPerDim);
            out.writeBytes(globalMax[d], 0, bytesPerDim);
        }
        if (!points.isEmpty()) {
            byte[] content = buildNode(points);
            out.writeVInt(content.length);
            out.writeBytes(content, 0, content.length);
        }
        CodecUtil.writeFooter(out);
    }

    private void computeBounds(List<Point> pts, byte[][] min, byte[][] max) {
        for (int d = 0; d < numDims; d++) {
            byte[] mn = pts.get(0).packedValue.clone();
            byte[] mx = pts.get(0).packedValue.clone();
            for (Point p : pts) {
                if (compareDim(p.packedValue, mn, d) < 0) {
                    mn = p.packedValue;
                }
                if (compareDim(p.packedValue, mx, d) > 0) {
                    mx = p.packedValue;
                }
            }
            min[d] = extractDim(mn, d);
            max[d] = extractDim(mx, d);
        }
    }

    private byte[] extractDim(byte[] packedValue, int dim) {
        byte[] result = new byte[bytesPerDim];
        System.arraycopy(packedValue, dim * bytesPerDim, result, 0, bytesPerDim);
        return result;
    }

    private int compareDim(byte[] a, byte[] b, int dim) {
        return NumericUtils.compareUnsigned(a, dim * bytesPerDim, b, dim * bytesPerDim, bytesPerDim);
    }

    private byte[] buildNode(List<Point> pts) throws IOException {
        BytesDataOutput out = new BytesDataOutput(pts.size() * (numDims * bytesPerDim + 8) + 64);
        byte[][] min = new byte[numDims][];
        byte[][] max = new byte[numDims][];
        computeBounds(pts, min, max);
        for (int d = 0; d < numDims; d++) {
            out.writeBytes(min[d], 0, bytesPerDim);
            out.writeBytes(max[d], 0, bytesPerDim);
        }
        if (pts.size() <= maxPointsInLeaf) {
            out.writeByte((byte) 1);
            writeLeaf(out, pts);
        } else {
            out.writeByte((byte) 0);
            int splitDim = 0;
            int bestSpread = -1;
            for (int d = 0; d < numDims; d++) {
                int spread = NumericUtils.compareUnsigned(max[d], 0, min[d], 0, bytesPerDim);
                if (spread > bestSpread) {
                    bestSpread = spread;
                    splitDim = d;
                }
            }
            final int sd = splitDim;
            pts.sort((a, b) -> compareDim(a.packedValue, b.packedValue, sd));
            int mid = pts.size() / 2;
            List<Point> left = new ArrayList<>(pts.subList(0, mid));
            List<Point> right = new ArrayList<>(pts.subList(mid, pts.size()));
            byte[] splitValue = extractDim(left.get(left.size() - 1).packedValue, splitDim);
            out.writeByte((byte) splitDim);
            out.writeBytes(splitValue, 0, bytesPerDim);
            byte[] leftContent = buildNode(left);
            out.writeVInt(leftContent.length);
            out.writeBytes(leftContent, 0, leftContent.length);
            byte[] rightContent = buildNode(right);
            out.writeVInt(rightContent.length);
            out.writeBytes(rightContent, 0, rightContent.length);
        }
        return out.toArrayCopy();
    }

    private void writeLeaf(BytesDataOutput out, List<Point> pts) throws IOException {
        out.writeVInt(pts.size());
        int[] prefixLen = new int[numDims];
        byte[] first = pts.get(0).packedValue;
        for (int d = 0; d < numDims; d++) {
            prefixLen[d] = bytesPerDim;
            for (Point p : pts) {
                int common = commonPrefix(first, p.packedValue, d);
                prefixLen[d] = Math.min(prefixLen[d], common);
            }
            out.writeByte((byte) prefixLen[d]);
            out.writeBytes(first, d * bytesPerDim, prefixLen[d]);
        }
        for (Point p : pts) {
            for (int d = 0; d < numDims; d++) {
                int base = d * bytesPerDim;
                out.writeBytes(p.packedValue, base + prefixLen[d], bytesPerDim - prefixLen[d]);
            }
            out.writeVInt(p.docId);
        }
    }

    private int commonPrefix(byte[] a, byte[] b, int dim) {
        int base = dim * bytesPerDim;
        int i = 0;
        while (i < bytesPerDim && a[base + i] == b[base + i]) {
            i++;
        }
        return i;
    }
}
