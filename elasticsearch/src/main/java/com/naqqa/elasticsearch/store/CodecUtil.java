package com.naqqa.elasticsearch.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;

public final class CodecUtil {

    public static final int CODEC_MAGIC = 0x3fd76c17;
    public static final int FOOTER_MAGIC = ~CODEC_MAGIC;
    public static final int ID_LENGTH = 16;
    public static final int ALGORITHM_CRC32C = 1;

    private CodecUtil() {
    }

    public record IndexHeader(int version, byte[] id, String suffix) {
    }

    public static void writeHeader(DataOutput out, String codec, int version) throws IOException {
        byte[] bytes = codec.getBytes(StandardCharsets.UTF_8);
        if (bytes.length != codec.length() || bytes.length >= 128) {
            throw new IllegalArgumentException("codec must be simple ASCII, less than 128 characters in length [got " + codec + "]");
        }
        out.writeInt(CODEC_MAGIC);
        out.writeString(codec);
        out.writeInt(version);
    }

    public static void writeIndexHeader(DataOutput out, String codec, int version, byte[] id, String suffix) throws IOException {
        if (id.length != ID_LENGTH) {
            throw new IllegalArgumentException("Invalid id: " + idToString(id));
        }
        writeHeader(out, codec, version);
        out.writeBytes(id, 0, id.length);
        byte[] suffixBytes = suffix.getBytes(StandardCharsets.UTF_8);
        if (suffixBytes.length != suffix.length() || suffixBytes.length >= 256) {
            throw new IllegalArgumentException("suffix must be simple ASCII, less than 256 characters in length [got " + suffix + "]");
        }
        out.writeByte((byte) suffixBytes.length);
        out.writeBytes(suffixBytes, 0, suffixBytes.length);
    }

    public static int headerLength(String codec) {
        return 9 + codec.length();
    }

    public static int indexHeaderLength(String codec, String suffix) {
        return headerLength(codec) + ID_LENGTH + 1 + suffix.length();
    }

    public static int checkHeader(DataInput in, String codec, int minVersion, int maxVersion) throws IOException {
        int actualHeader = in.readInt();
        if (actualHeader != CODEC_MAGIC) {
            throw new CorruptIndexException("codec header mismatch: actual header=" + actualHeader + " vs expected header=" + CODEC_MAGIC, in);
        }
        return checkHeaderNoMagic(in, codec, minVersion, maxVersion);
    }

    public static int checkHeaderNoMagic(DataInput in, String codec, int minVersion, int maxVersion) throws IOException {
        String actualCodec;
        try {
            actualCodec = in.readString();
        } catch (IOException e) {
            throw new CorruptIndexException("invalid codec name in header", in, e);
        }
        if (!actualCodec.equals(codec)) {
            throw new CorruptIndexException("codec mismatch: actual codec=" + actualCodec + " vs expected codec=" + codec, in);
        }
        int actualVersion = in.readInt();
        if (actualVersion < minVersion) {
            throw new IndexFormatTooOldException(in, actualVersion, minVersion, maxVersion);
        }
        if (actualVersion > maxVersion) {
            throw new IndexFormatTooNewException(in, actualVersion, minVersion, maxVersion);
        }
        return actualVersion;
    }

    public static int checkIndexHeader(DataInput in, String codec, int minVersion, int maxVersion, byte[] expectedID, String expectedSuffix) throws IOException {
        int version = checkHeader(in, codec, minVersion, maxVersion);
        checkIndexHeaderID(in, expectedID);
        checkIndexHeaderSuffix(in, expectedSuffix);
        return version;
    }

    public static IndexHeader readIndexHeader(DataInput in, String codec, int minVersion, int maxVersion) throws IOException {
        int version = checkHeader(in, codec, minVersion, maxVersion);
        byte[] id = new byte[ID_LENGTH];
        in.readBytes(id, 0, ID_LENGTH);
        int suffixLength = in.readByte() & 0xFF;
        byte[] suffixBytes = new byte[suffixLength];
        in.readBytes(suffixBytes, 0, suffixLength);
        return new IndexHeader(version, id, new String(suffixBytes, StandardCharsets.UTF_8));
    }

    public static byte[] checkIndexHeaderID(DataInput in, byte[] expectedID) throws IOException {
        byte[] id = new byte[ID_LENGTH];
        in.readBytes(id, 0, id.length);
        if (expectedID != null && !Arrays.equals(id, expectedID)) {
            throw new CorruptIndexException("file mismatch, expected id=" + idToString(expectedID) + ", got=" + idToString(id), in);
        }
        return id;
    }

    public static String checkIndexHeaderSuffix(DataInput in, String expectedSuffix) throws IOException {
        int suffixLength = in.readByte() & 0xFF;
        byte[] suffixBytes = new byte[suffixLength];
        in.readBytes(suffixBytes, 0, suffixBytes.length);
        String suffix = new String(suffixBytes, StandardCharsets.UTF_8);
        if (expectedSuffix != null && !suffix.equals(expectedSuffix)) {
            throw new CorruptIndexException("file mismatch, expected suffix=" + expectedSuffix + ", got=" + suffix, in);
        }
        return suffix;
    }

    public static void writeFooter(IndexOutput out) throws IOException {
        out.writeInt(FOOTER_MAGIC);
        out.writeInt(ALGORITHM_CRC32C);
        writeCRC(out);
    }

    public static int footerLength() {
        return 16;
    }

    public static long checkFooter(ChecksumIndexInput in) throws IOException {
        validateFooter(in);
        long actualChecksum = in.getChecksum();
        long expectedChecksum = readCRC(in);
        if (expectedChecksum != actualChecksum) {
            throw new CorruptIndexException("checksum failed (hardware problem?) : expected=" + Long.toHexString(expectedChecksum)
                + " actual=" + Long.toHexString(actualChecksum), in);
        }
        return actualChecksum;
    }

    public static void checkFooter(ChecksumIndexInput in, Throwable priorException) throws IOException {
        if (priorException == null) {
            checkFooter(in);
            return;
        }
        try {
            long remaining = in.length() - in.getFilePointer();
            if (remaining < footerLength()) {
                priorException.addSuppressed(new CorruptIndexException("checksum status indeterminate: remaining=" + remaining, in));
            } else {
                in.skipBytes(remaining - footerLength());
                try {
                    validateFooter(in);
                    long actualChecksum = in.getChecksum();
                    long expectedChecksum = readCRC(in);
                    if (expectedChecksum != actualChecksum) {
                        priorException.addSuppressed(new CorruptIndexException("checksum failed : expected=" + Long.toHexString(expectedChecksum)
                            + " actual=" + Long.toHexString(actualChecksum), in));
                    } else {
                        priorException.addSuppressed(new CorruptIndexException("checksum passed, unexpected exception during read", in));
                    }
                } catch (IOException t) {
                    priorException.addSuppressed(t);
                }
            }
        } catch (IOException | RuntimeException t) {
            priorException.addSuppressed(t);
        }
        if (priorException instanceof IOException io) {
            throw io;
        }
        if (priorException instanceof RuntimeException re) {
            throw re;
        }
        if (priorException instanceof Error e) {
            throw e;
        }
        throw new IOException(priorException);
    }

    public static long retrieveChecksum(IndexInput in) throws IOException {
        if (in.length() < footerLength()) {
            throw new CorruptIndexException("misplaced codec footer (file truncated?): length=" + in.length()
                + " but footerLength==" + footerLength(), in);
        }
        in.seek(in.length() - footerLength());
        validateFooter(in);
        return readCRC(in);
    }

    public static long retrieveChecksum(IndexInput in, long expectedLength) throws IOException {
        if (expectedLength < footerLength()) {
            throw new IllegalArgumentException("expectedLength cannot be less than the footer length");
        }
        if (in.length() < expectedLength) {
            throw new CorruptIndexException("truncated file: length=" + in.length() + " but expectedLength==" + expectedLength, in);
        }
        if (in.length() > expectedLength) {
            throw new CorruptIndexException("file too long: length=" + in.length() + " but expectedLength==" + expectedLength, in);
        }
        return retrieveChecksum(in);
    }

    public static long checksumEntireFile(IndexInput input) throws IOException {
        IndexInput clone = input.clone();
        clone.seek(0);
        ChecksumIndexInput in = new BufferedChecksumIndexInput(clone);
        if (in.length() < footerLength()) {
            throw new CorruptIndexException("misplaced codec footer (file truncated?): length=" + in.length()
                + " but footerLength==" + footerLength(), input);
        }
        in.seek(in.length() - footerLength());
        return checkFooter(in);
    }

    private static void validateFooter(IndexInput in) throws IOException {
        long remaining = in.length() - in.getFilePointer();
        long expected = footerLength();
        if (remaining < expected) {
            throw new CorruptIndexException("misplaced codec footer (file truncated?): remaining=" + remaining
                + ", expected=" + expected + ", fp=" + in.getFilePointer(), in);
        } else if (remaining > expected) {
            throw new CorruptIndexException("misplaced codec footer (file extended?): remaining=" + remaining
                + ", expected=" + expected + ", fp=" + in.getFilePointer(), in);
        }
        int magic = in.readInt();
        if (magic != FOOTER_MAGIC) {
            throw new CorruptIndexException("codec footer mismatch (file truncated?): actual footer=" + magic
                + " vs expected footer=" + FOOTER_MAGIC, in);
        }
        int algorithmID = in.readInt();
        if (algorithmID != ALGORITHM_CRC32C) {
            throw new CorruptIndexException("codec footer mismatch: unknown algorithmID: " + algorithmID, in);
        }
    }

    static void writeCRC(IndexOutput output) throws IOException {
        long value = output.getChecksum();
        if ((value & 0xFFFFFFFF00000000L) != 0) {
            throw new IllegalStateException("Illegal CRC-32 checksum: " + value + " (resource=" + output + ")");
        }
        output.writeLong(value);
    }

    static long readCRC(IndexInput input) throws IOException {
        long value = input.readLong();
        if ((value & 0xFFFFFFFF00000000L) != 0) {
            throw new CorruptIndexException("Illegal CRC-32 checksum: " + value, input);
        }
        return value;
    }

    public static String idToString(byte[] id) {
        if (id == null) {
            return "(null)";
        }
        return HexFormat.of().formatHex(id);
    }
}
