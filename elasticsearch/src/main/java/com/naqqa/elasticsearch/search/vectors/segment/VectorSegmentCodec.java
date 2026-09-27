package com.naqqa.elasticsearch.search.vectors.segment;

import com.naqqa.elasticsearch.search.vectors.ArrayFloatVectorValues;
import com.naqqa.elasticsearch.search.vectors.ByteVectorValues;
import com.naqqa.elasticsearch.search.vectors.ElementType;
import com.naqqa.elasticsearch.search.vectors.FloatVectorValues;
import com.naqqa.elasticsearch.search.vectors.RandomVectorScorerSupplier;
import com.naqqa.elasticsearch.search.vectors.VectorScorers;
import com.naqqa.elasticsearch.search.vectors.VectorSimilarity;
import com.naqqa.elasticsearch.search.vectors.hnsw.HnswConfig;
import com.naqqa.elasticsearch.search.vectors.hnsw.HnswGraph;
import com.naqqa.elasticsearch.search.vectors.hnsw.HnswGraphBuilder;
import com.naqqa.elasticsearch.search.vectors.quantization.BinaryQuantizer;
import com.naqqa.elasticsearch.search.vectors.quantization.ScalarQuantizer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

final class VectorSegmentCodec {

    static final int BLOB_VERSION = 1;

    private VectorSegmentCodec() {
    }

    static final class SegmentGraphData {
        ElementType elementType;
        QuantizationMode quantization;
        int dims;
        int[] ordToDoc;
        HnswGraph graph;

        ScalarQuantizer scalarQuantizer;
        byte[][] scalarQuantizedByOrd;
        float[] scalarCorrectionByOrd;

        BinaryQuantizer binaryQuantizer;
        float[] binaryCentroid;
        byte[][] binaryBitsByOrd;
        float[] binaryCentroidDotByOrd;
        float[] binaryResidualScaleByOrd;
        int[] binaryPopcountByOrd;
    }

    static SegmentGraphData buildFloat(float[][] vectorsByOrd, int[] ordToDoc, VectorSimilarity similarity,
                                        HnswConfig config, QuantizationMode quantization) {
        SegmentGraphData data = buildFloatQuantizationOnly(vectorsByOrd, ordToDoc, similarity, quantization);
        FloatVectorValues values = new ArrayFloatVectorValues(vectorsByOrd, ordToDoc);
        RandomVectorScorerSupplier supplier = VectorScorers.floatSupplier(values, similarity);
        data.graph = new HnswGraphBuilder(supplier, config).build();
        return data;
    }

    static SegmentGraphData buildBytes(byte[][] vectorsByOrd, int[] ordToDoc, ElementType elementType,
                                        VectorSimilarity similarity, HnswConfig config) {
        SegmentGraphData data = new SegmentGraphData();
        data.elementType = elementType;
        data.quantization = QuantizationMode.NONE;
        data.dims = vectorsByOrd.length == 0 ? 0 : vectorsByOrd[0].length;
        data.ordToDoc = ordToDoc;
        ByteVectorValues values = elementType == ElementType.BIT
            ? ByteVectorValues.bits(vectorsByOrd, ordToDoc)
            : ByteVectorValues.of(vectorsByOrd, ordToDoc);
        RandomVectorScorerSupplier supplier = VectorScorers.byteSupplier(values, similarity);
        data.graph = new HnswGraphBuilder(supplier, config).build();
        return data;
    }

    static SegmentGraphData buildFloatFromInitializer(float[][] vectorsByOrd, int[] ordToDoc, VectorSimilarity similarity,
                                                        HnswConfig config, QuantizationMode quantization,
                                                        HnswGraph initializer, int[] oldToNewOrd) {
        SegmentGraphData data = buildFloatQuantizationOnly(vectorsByOrd, ordToDoc, similarity, quantization);
        FloatVectorValues values = new ArrayFloatVectorValues(vectorsByOrd, ordToDoc);
        RandomVectorScorerSupplier supplier = VectorScorers.floatSupplier(values, similarity);
        HnswGraphBuilder builder = HnswGraphBuilder.fromExisting(supplier, config, initializer, oldToNewOrd);
        data.graph = builder.build();
        return data;
    }

    static SegmentGraphData buildBytesFromInitializer(byte[][] vectorsByOrd, int[] ordToDoc, ElementType elementType,
                                                        VectorSimilarity similarity, HnswConfig config,
                                                        HnswGraph initializer, int[] oldToNewOrd) {
        SegmentGraphData data = new SegmentGraphData();
        data.elementType = elementType;
        data.quantization = QuantizationMode.NONE;
        data.dims = vectorsByOrd.length == 0 ? 0 : vectorsByOrd[0].length;
        data.ordToDoc = ordToDoc;
        ByteVectorValues values = elementType == ElementType.BIT
            ? ByteVectorValues.bits(vectorsByOrd, ordToDoc)
            : ByteVectorValues.of(vectorsByOrd, ordToDoc);
        RandomVectorScorerSupplier supplier = VectorScorers.byteSupplier(values, similarity);
        HnswGraphBuilder builder = HnswGraphBuilder.fromExisting(supplier, config, initializer, oldToNewOrd);
        data.graph = builder.build();
        return data;
    }

    private static SegmentGraphData buildFloatQuantizationOnly(float[][] vectorsByOrd, int[] ordToDoc,
                                                                 VectorSimilarity similarity, QuantizationMode quantization) {
        SegmentGraphData data = new SegmentGraphData();
        data.elementType = ElementType.FLOAT;
        data.quantization = quantization;
        data.dims = vectorsByOrd.length == 0 ? 0 : vectorsByOrd[0].length;
        data.ordToDoc = ordToDoc;
        int n = vectorsByOrd.length;
        if (quantization == QuantizationMode.INT8 || quantization == QuantizationMode.INT4) {
            int bits = quantization == QuantizationMode.INT8 ? 8 : 4;
            float confidence = n == 0 ? 0.99f : ScalarQuantizer.defaultConfidenceInterval(data.dims);
            ScalarQuantizer sq = ScalarQuantizer.fromVectors(vectorsByOrd, confidence, bits);
            byte[][] quantized = new byte[n][];
            float[] corrections = new float[n];
            for (int i = 0; i < n; i++) {
                quantized[i] = sq.quantize(vectorsByOrd[i]);
                corrections[i] = sq.correction(quantized[i]);
            }
            data.scalarQuantizer = sq;
            data.scalarQuantizedByOrd = quantized;
            data.scalarCorrectionByOrd = corrections;
        } else if (quantization == QuantizationMode.BINARY) {
            float[] centroid = new float[data.dims];
            for (float[] v : vectorsByOrd) {
                for (int d = 0; d < data.dims; d++) {
                    centroid[d] += v[d];
                }
            }
            if (n > 0) {
                for (int d = 0; d < data.dims; d++) {
                    centroid[d] /= n;
                }
            }
            BinaryQuantizer bq = new BinaryQuantizer(centroid);
            byte[][] bits = new byte[n][];
            float[] centroidDot = new float[n];
            float[] residualScale = new float[n];
            int[] popcount = new int[n];
            for (int i = 0; i < n; i++) {
                BinaryQuantizer.Quantized q = bq.quantize(vectorsByOrd[i]);
                bits[i] = q.bits();
                centroidDot[i] = q.centroidDot();
                residualScale[i] = q.residualScale();
                popcount[i] = q.popcount();
            }
            data.binaryQuantizer = bq;
            data.binaryCentroid = centroid;
            data.binaryBitsByOrd = bits;
            data.binaryCentroidDotByOrd = centroidDot;
            data.binaryResidualScaleByOrd = residualScale;
            data.binaryPopcountByOrd = popcount;
        }
        return data;
    }

    static byte[] encode(SegmentGraphData data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeByte(BLOB_VERSION);
        out.writeByte(data.elementType.ordinal());
        out.writeByte(data.quantization.ordinal());
        out.writeInt(data.dims);
        out.writeInt(data.ordToDoc.length);
        for (int doc : data.ordToDoc) {
            out.writeInt(doc);
        }
        if (data.quantization == QuantizationMode.INT8 || data.quantization == QuantizationMode.INT4) {
            out.writeFloat(data.scalarQuantizer.lowerQuantile());
            out.writeFloat(data.scalarQuantizer.upperQuantile());
            out.writeInt(data.scalarQuantizer.bits());
            for (int i = 0; i < data.scalarQuantizedByOrd.length; i++) {
                out.write(data.scalarQuantizedByOrd[i]);
                out.writeFloat(data.scalarCorrectionByOrd[i]);
            }
        } else if (data.quantization == QuantizationMode.BINARY) {
            for (float f : data.binaryCentroid) {
                out.writeFloat(f);
            }
            for (int i = 0; i < data.binaryBitsByOrd.length; i++) {
                out.write(data.binaryBitsByOrd[i]);
                out.writeFloat(data.binaryCentroidDotByOrd[i]);
                out.writeFloat(data.binaryResidualScaleByOrd[i]);
                out.writeInt(data.binaryPopcountByOrd[i]);
            }
        }
        data.graph.writeTo(out);
        out.flush();
        return bos.toByteArray();
    }

    static SegmentGraphData decode(byte[] blob) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(blob));
        int version = in.readByte();
        if (version != BLOB_VERSION) {
            throw new IOException("unsupported vector segment blob version " + version);
        }
        SegmentGraphData data = new SegmentGraphData();
        data.elementType = ElementType.values()[in.readByte()];
        data.quantization = QuantizationMode.fromCode(in.readByte());
        data.dims = in.readInt();
        int ordCount = in.readInt();
        int[] ordToDoc = new int[ordCount];
        for (int i = 0; i < ordCount; i++) {
            ordToDoc[i] = in.readInt();
        }
        data.ordToDoc = ordToDoc;
        if (data.quantization == QuantizationMode.INT8 || data.quantization == QuantizationMode.INT4) {
            float lower = in.readFloat();
            float upper = in.readFloat();
            int bits = in.readInt();
            ScalarQuantizer sq = new ScalarQuantizer(lower, upper, bits);
            byte[][] quantized = new byte[ordCount][];
            float[] corrections = new float[ordCount];
            for (int i = 0; i < ordCount; i++) {
                byte[] q = new byte[data.dims];
                in.readFully(q);
                quantized[i] = q;
                corrections[i] = in.readFloat();
            }
            data.scalarQuantizer = sq;
            data.scalarQuantizedByOrd = quantized;
            data.scalarCorrectionByOrd = corrections;
        } else if (data.quantization == QuantizationMode.BINARY) {
            float[] centroid = new float[data.dims];
            for (int d = 0; d < data.dims; d++) {
                centroid[d] = in.readFloat();
            }
            BinaryQuantizer bq = new BinaryQuantizer(centroid);
            int bitLength = (data.dims + 7) / 8;
            byte[][] bits = new byte[ordCount][];
            float[] centroidDot = new float[ordCount];
            float[] residualScale = new float[ordCount];
            int[] popcount = new int[ordCount];
            for (int i = 0; i < ordCount; i++) {
                byte[] b = new byte[bitLength];
                in.readFully(b);
                bits[i] = b;
                centroidDot[i] = in.readFloat();
                residualScale[i] = in.readFloat();
                popcount[i] = in.readInt();
            }
            data.binaryQuantizer = bq;
            data.binaryCentroid = centroid;
            data.binaryBitsByOrd = bits;
            data.binaryCentroidDotByOrd = centroidDot;
            data.binaryResidualScaleByOrd = residualScale;
            data.binaryPopcountByOrd = popcount;
        }
        data.graph = HnswGraph.readFrom(in);
        return data;
    }
}
