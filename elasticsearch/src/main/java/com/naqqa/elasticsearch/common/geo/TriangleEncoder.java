package com.naqqa.elasticsearch.common.geo;

public final class TriangleEncoder {

    public static final int NUM_DIMS = 7;
    public static final int BYTES_PER_DIM = 4;
    public static final int BYTES = NUM_DIMS * BYTES_PER_DIM;

    private TriangleEncoder() {
    }

    public record EncodedTriangle(int aY, int aX, boolean abFromShape,
                                   int bY, int bX, boolean bcFromShape,
                                   int cY, int cX, boolean caFromShape) {
    }

    public static byte[] encode(int aY, int aX, boolean abFromShape,
                                 int bY, int bX, boolean bcFromShape,
                                 int cY, int cX, boolean caFromShape) {
        int[][] pts = {{aY, aX}, {bY, bX}, {cY, cX}};
        boolean[] edges = {abFromShape, bcFromShape, caFromShape};
        int min = 0;
        for (int i = 1; i < 3; i++) {
            if (pts[i][0] < pts[min][0] || (pts[i][0] == pts[min][0] && pts[i][1] < pts[min][1])) {
                min = i;
            }
        }
        int[][] rp = new int[3][2];
        boolean[] re = new boolean[3];
        for (int i = 0; i < 3; i++) {
            rp[i] = pts[(min + i) % 3];
            re[i] = edges[(min + i) % 3];
        }
        long cross = crossProduct(rp);
        int orientation = cross >= 0 ? 1 : 0;
        int metadata = (re[0] ? 1 : 0) | (re[1] ? 2 : 0) | (re[2] ? 4 : 0) | (orientation << 3);

        byte[] bytes = new byte[BYTES];
        writeDim(bytes, 0, rp[0][0]);
        writeDim(bytes, 1, rp[0][1]);
        writeDim(bytes, 2, rp[1][0]);
        writeDim(bytes, 3, rp[1][1]);
        writeDim(bytes, 4, rp[2][0]);
        writeDim(bytes, 5, rp[2][1]);
        writeDim(bytes, 6, metadata);
        return bytes;
    }

    private static long crossProduct(int[][] p) {
        long ax = p[1][1] - p[0][1];
        long ay = p[1][0] - p[0][0];
        long bx = p[2][1] - p[0][1];
        long by = p[2][0] - p[0][0];
        return ax * by - ay * bx;
    }

    public static EncodedTriangle decode(byte[] bytes) {
        int aY = readDim(bytes, 0);
        int aX = readDim(bytes, 1);
        int bY = readDim(bytes, 2);
        int bX = readDim(bytes, 3);
        int cY = readDim(bytes, 4);
        int cX = readDim(bytes, 5);
        int metadata = readDim(bytes, 6);
        boolean ab = (metadata & 1) != 0;
        boolean bc = (metadata & 2) != 0;
        boolean ca = (metadata & 4) != 0;
        return new EncodedTriangle(aY, aX, ab, bY, bX, bc, cY, cX, ca);
    }

    public static int minX(byte[] bytes) {
        return Math.min(readDim(bytes, 1), Math.min(readDim(bytes, 3), readDim(bytes, 5)));
    }

    public static int maxX(byte[] bytes) {
        return Math.max(readDim(bytes, 1), Math.max(readDim(bytes, 3), readDim(bytes, 5)));
    }

    public static int minY(byte[] bytes) {
        return Math.min(readDim(bytes, 0), Math.min(readDim(bytes, 2), readDim(bytes, 4)));
    }

    public static int maxY(byte[] bytes) {
        return Math.max(readDim(bytes, 0), Math.max(readDim(bytes, 2), readDim(bytes, 4)));
    }

    private static void writeDim(byte[] bytes, int dim, int value) {
        int sortable = value ^ 0x80000000;
        int offset = dim * BYTES_PER_DIM;
        bytes[offset] = (byte) (sortable >>> 24);
        bytes[offset + 1] = (byte) (sortable >>> 16);
        bytes[offset + 2] = (byte) (sortable >>> 8);
        bytes[offset + 3] = (byte) sortable;
    }

    private static int readDim(byte[] bytes, int dim) {
        int offset = dim * BYTES_PER_DIM;
        int sortable = ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16)
            | ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
        return sortable ^ 0x80000000;
    }
}
