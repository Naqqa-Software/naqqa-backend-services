package com.naqqa.elasticsearch.codec.points;

import com.naqqa.elasticsearch.store.CodecUtil;
import com.naqqa.elasticsearch.store.IndexInput;

import java.io.IOException;

public final class BKDReader {

    private final IndexInput in;
    private final int numDims;
    private final int bytesPerDim;
    private final int numPoints;
    private final byte[][] globalMin;
    private final byte[][] globalMax;
    private final long treeStart;
    private final int treeLength;

    public BKDReader(IndexInput in) throws IOException {
        CodecUtil.checksumEntireFile(in);
        this.in = in.clone();
        CodecUtil.checkHeader(this.in, BKDWriter.CODEC_NAME, BKDWriter.VERSION_START, BKDWriter.VERSION_CURRENT);
        this.numDims = this.in.readVInt();
        this.bytesPerDim = this.in.readVInt();
        this.numPoints = this.in.readVInt();
        this.globalMin = new byte[numDims][];
        this.globalMax = new byte[numDims][];
        for (int d = 0; d < numDims; d++) {
            globalMin[d] = new byte[bytesPerDim];
            this.in.readBytes(globalMin[d], 0, bytesPerDim);
            globalMax[d] = new byte[bytesPerDim];
            this.in.readBytes(globalMax[d], 0, bytesPerDim);
        }
        if (numPoints > 0) {
            this.treeLength = this.in.readVInt();
            this.treeStart = this.in.getFilePointer();
        } else {
            this.treeLength = 0;
            this.treeStart = this.in.getFilePointer();
        }
    }

    public int numPoints() {
        return numPoints;
    }

    public int numDims() {
        return numDims;
    }

    public int bytesPerDim() {
        return bytesPerDim;
    }

    public byte[] minPackedValue(int dim) {
        return globalMin[dim].clone();
    }

    public byte[] maxPackedValue(int dim) {
        return globalMax[dim].clone();
    }

    public void intersect(IntersectVisitor visitor) throws IOException {
        if (numPoints == 0) {
            return;
        }
        IndexInput cursor = in.clone();
        cursor.seek(treeStart);
        visitNode(cursor, visitor, treeStart + treeLength);
    }

    public long estimatePointCount(IntersectVisitor visitor) throws IOException {
        if (numPoints == 0) {
            return 0;
        }
        IndexInput cursor = in.clone();
        cursor.seek(treeStart);
        return estimateNode(cursor, visitor, treeStart + treeLength);
    }

    private byte[][][] readBounds(IndexInput cursor) throws IOException {
        byte[][] min = new byte[numDims][];
        byte[][] max = new byte[numDims][];
        for (int d = 0; d < numDims; d++) {
            min[d] = new byte[bytesPerDim];
            cursor.readBytes(min[d], 0, bytesPerDim);
            max[d] = new byte[bytesPerDim];
            cursor.readBytes(max[d], 0, bytesPerDim);
        }
        return new byte[][][] {min, max};
    }

    private void visitNode(IndexInput cursor, IntersectVisitor visitor, long nodeEnd) throws IOException {
        byte[][][] bounds = readBounds(cursor);
        byte[][] min = bounds[0];
        byte[][] max = bounds[1];
        Relation rel = visitor.compare(min, max);
        if (rel == Relation.CELL_OUTSIDE_QUERY) {
            cursor.seek(nodeEnd);
            return;
        }
        byte type = cursor.readByte();
        if (type == 1) {
            visitLeaf(cursor, visitor, rel);
        } else {
            cursor.readByte();
            cursor.skipBytes(bytesPerDim);
            int leftLen = cursor.readVInt();
            long leftEnd = cursor.getFilePointer() + leftLen;
            visitNode(cursor, visitor, leftEnd);
            cursor.seek(leftEnd);
            int rightLen = cursor.readVInt();
            long rightEnd = cursor.getFilePointer() + rightLen;
            visitNode(cursor, visitor, rightEnd);
        }
        cursor.seek(nodeEnd);
    }

    private void visitLeaf(IndexInput cursor, IntersectVisitor visitor, Relation rel) throws IOException {
        int count = cursor.readVInt();
        int[] prefixLen = new int[numDims];
        byte[] packed = new byte[numDims * bytesPerDim];
        for (int d = 0; d < numDims; d++) {
            prefixLen[d] = cursor.readByte() & 0xFF;
            cursor.readBytes(packed, d * bytesPerDim, prefixLen[d]);
        }
        for (int i = 0; i < count; i++) {
            for (int d = 0; d < numDims; d++) {
                int base = d * bytesPerDim;
                cursor.readBytes(packed, base + prefixLen[d], bytesPerDim - prefixLen[d]);
            }
            int docId = cursor.readVInt();
            if (rel == Relation.CELL_INSIDE_QUERY) {
                visitor.visit(docId);
            } else {
                visitor.visit(docId, packed.clone());
            }
        }
    }

    private long estimateNode(IndexInput cursor, IntersectVisitor visitor, long nodeEnd) throws IOException {
        byte[][][] bounds = readBounds(cursor);
        byte[][] min = bounds[0];
        byte[][] max = bounds[1];
        Relation rel = visitor.compare(min, max);
        if (rel == Relation.CELL_OUTSIDE_QUERY) {
            cursor.seek(nodeEnd);
            return 0;
        }
        byte type = cursor.readByte();
        long result;
        if (type == 1) {
            int count = cursor.readVInt();
            result = count;
            cursor.seek(nodeEnd);
        } else {
            cursor.readByte();
            cursor.skipBytes(bytesPerDim);
            int leftLen = cursor.readVInt();
            long leftEnd = cursor.getFilePointer() + leftLen;
            long leftCount = estimateNode(cursor, visitor, leftEnd);
            cursor.seek(leftEnd);
            int rightLen = cursor.readVInt();
            long rightEnd = cursor.getFilePointer() + rightLen;
            long rightCount = estimateNode(cursor, visitor, rightEnd);
            result = leftCount + rightCount;
            cursor.seek(nodeEnd);
        }
        return result;
    }
}
