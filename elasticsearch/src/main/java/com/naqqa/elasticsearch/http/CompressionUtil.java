package com.naqqa.elasticsearch.http;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

public final class CompressionUtil {

    private CompressionUtil() {
    }

    public enum Encoding { GZIP, DEFLATE, IDENTITY }

    public static Encoding negotiateResponseEncoding(String acceptEncodingHeader) {
        if (acceptEncodingHeader == null || acceptEncodingHeader.isEmpty()) {
            return Encoding.IDENTITY;
        }
        double gzipQ = -1;
        double deflateQ = -1;
        for (String token : acceptEncodingHeader.split(",")) {
            String[] parts = token.trim().split(";");
            String name = parts[0].trim().toLowerCase(Locale.ROOT);
            double q = 1.0;
            for (int i = 1; i < parts.length; i++) {
                String param = parts[i].trim();
                if (param.startsWith("q=")) {
                    try {
                        q = Double.parseDouble(param.substring(2));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (name.equals("gzip") || name.equals("*")) {
                gzipQ = Math.max(gzipQ, q);
            }
            if (name.equals("deflate")) {
                deflateQ = Math.max(deflateQ, q);
            }
        }
        if (gzipQ > 0) {
            return Encoding.GZIP;
        }
        if (deflateQ > 0) {
            return Encoding.DEFLATE;
        }
        return Encoding.IDENTITY;
    }

    public static byte[] compress(byte[] data, Encoding encoding) throws IOException {
        if (encoding == Encoding.IDENTITY || data.length == 0) {
            return data;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(32, data.length / 2));
        if (encoding == Encoding.GZIP) {
            try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
                gzip.write(data);
            }
        } else {
            Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, false);
            try (DeflaterOutputStream deflate = new DeflaterOutputStream(out, deflater)) {
                deflate.write(data);
            } finally {
                deflater.end();
            }
        }
        return out.toByteArray();
    }

    public static byte[] gunzip(byte[] data) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return in.readAllBytes();
        }
    }

    public static byte[] inflate(byte[] data) throws IOException {
        Inflater inflater = new Inflater(false);
        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(data), inflater)) {
            return in.readAllBytes();
        } finally {
            inflater.end();
        }
    }

    public static String encodingToken(Encoding encoding) {
        return switch (encoding) {
            case GZIP -> "gzip";
            case DEFLATE -> "deflate";
            case IDENTITY -> null;
        };
    }
}
