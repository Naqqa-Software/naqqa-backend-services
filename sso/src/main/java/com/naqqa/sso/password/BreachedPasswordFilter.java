package com.naqqa.sso.password;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.util.Arrays;

public final class BreachedPasswordFilter {
    public static final int SHA1_BYTES = 20;
    public static final int MIN_PREFIX_BYTES = 8;
    public static final int DEFAULT_PREFIX_BYTES = 10;
    public static final long MAX_BITS = 1L << 33;
    private static final int MAGIC = 0x4E514246;
    private static final int FORMAT_VERSION = 1;

    private final int prefixBytes;
    private final int hashFunctions;
    private final long bitCount;
    private final long[] bits;
    private long entries;

    private BreachedPasswordFilter(int prefixBytes, int hashFunctions, long bitCount, long[] bits, long entries) {
        this.prefixBytes = prefixBytes;
        this.hashFunctions = hashFunctions;
        this.bitCount = bitCount;
        this.bits = bits;
        this.entries = entries;
    }

    public static BreachedPasswordFilter create(int prefixBytes, long expectedEntries, double falsePositiveRate) {
        if (prefixBytes < MIN_PREFIX_BYTES || prefixBytes > SHA1_BYTES) {
            throw new SsoException("breached.prefix_invalid");
        }
        if (expectedEntries < 1 || !(falsePositiveRate > 0 && falsePositiveRate < 0.5)) {
            throw new SsoException("breached.params_invalid");
        }
        double ln2 = Math.log(2);
        double m = Math.ceil(-expectedEntries * Math.log(falsePositiveRate) / (ln2 * ln2));
        long mBits = Math.max(64, (long) m);
        if (mBits > MAX_BITS) {
            throw new SsoException("breached.too_large");
        }
        int k = (int) Math.max(1, Math.min(30, Math.round((double) mBits / expectedEntries * ln2)));
        long words = (mBits + 63) / 64;
        return new BreachedPasswordFilter(prefixBytes, k, words * 64, new long[(int) words], 0);
    }

    public static BreachedPasswordFilter fromSha1Prefixes(InputStream in, int prefixBytes, long expectedEntries, double falsePositiveRate) throws IOException {
        BreachedPasswordFilter f = create(prefixBytes, expectedEntries, falsePositiveRate);
        byte[] rec = new byte[prefixBytes];
        while (true) {
            int read = in.readNBytes(rec, 0, prefixBytes);
            if (read == 0) {
                break;
            }
            if (read != prefixBytes) {
                throw new SsoException("breached.truncated_record");
            }
            f.addPrefix(rec);
        }
        return f;
    }

    public static BreachedPasswordFilter fromHexLines(Reader reader, int prefixBytes, long expectedEntries, double falsePositiveRate) throws IOException {
        BreachedPasswordFilter f = create(prefixBytes, expectedEntries, falsePositiveRate);
        BufferedReader br = reader instanceof BufferedReader ? (BufferedReader) reader : new BufferedReader(reader);
        String line;
        while ((line = br.readLine()) != null) {
            line = line.strip();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            String hex = colon >= 0 ? line.substring(0, colon) : line;
            if (hex.length() < 2 * prefixBytes || hex.length() > 2 * SHA1_BYTES || hex.length() % 2 != 0) {
                throw new SsoException("breached.bad_line");
            }
            byte[] b = new byte[prefixBytes];
            for (int i = 0; i < prefixBytes; i++) {
                int hi = Character.digit(hex.charAt(2 * i), 16);
                int lo = Character.digit(hex.charAt(2 * i + 1), 16);
                if (hi < 0 || lo < 0) {
                    throw new SsoException("breached.bad_line");
                }
                b[i] = (byte) ((hi << 4) | lo);
            }
            f.addPrefix(b);
        }
        return f;
    }

    public static BreachedPasswordFilter readFrom(InputStream in) throws IOException {
        DataInputStream din = new DataInputStream(in);
        try {
            if (din.readInt() != MAGIC || din.readUnsignedByte() != FORMAT_VERSION) {
                throw new SsoException("breached.format");
            }
            int prefix = din.readUnsignedByte();
            int k = din.readUnsignedByte();
            long m = din.readLong();
            long n = din.readLong();
            if (prefix < MIN_PREFIX_BYTES || prefix > SHA1_BYTES || k < 1 || k > 30 || m < 64 || m > MAX_BITS || m % 64 != 0 || n < 0) {
                throw new SsoException("breached.format");
            }
            long[] bits = new long[(int) (m / 64)];
            for (int i = 0; i < bits.length; i++) {
                bits[i] = din.readLong();
            }
            if (din.read() != -1) {
                throw new SsoException("breached.format");
            }
            return new BreachedPasswordFilter(prefix, k, m, bits, n);
        } catch (EOFException e) {
            throw new SsoException("breached.format");
        }
    }

    public void writeTo(OutputStream out) throws IOException {
        DataOutputStream dout = new DataOutputStream(out);
        dout.writeInt(MAGIC);
        dout.writeByte(FORMAT_VERSION);
        dout.writeByte(prefixBytes);
        dout.writeByte(hashFunctions);
        dout.writeLong(bitCount);
        dout.writeLong(entries);
        for (long w : bits) {
            dout.writeLong(w);
        }
        dout.flush();
    }

    public static byte[] sha1Prefix(String password, int prefixBytes) {
        return Arrays.copyOf(Hashes.digest("SHA-1", Bytes.utf8(password)), prefixBytes);
    }

    public synchronized void addPassword(String password) {
        addPrefix(sha1Prefix(password, prefixBytes));
    }

    public synchronized void addPrefix(byte[] prefix) {
        if (prefix.length != prefixBytes) {
            throw new SsoException("breached.prefix_invalid");
        }
        long[] idx = indexes(prefix);
        for (long i : idx) {
            bits[(int) (i >>> 6)] |= 1L << (i & 63);
        }
        entries++;
    }

    public boolean mightBeBreached(String password) {
        if (password == null) {
            return false;
        }
        return containsPrefix(sha1Prefix(password, prefixBytes));
    }

    public synchronized boolean containsPrefix(byte[] prefix) {
        if (prefix.length != prefixBytes) {
            return false;
        }
        for (long i : indexes(prefix)) {
            if ((bits[(int) (i >>> 6)] & (1L << (i & 63))) == 0) {
                return false;
            }
        }
        return true;
    }

    private long[] indexes(byte[] prefix) {
        byte[] h = Hashes.sha256(prefix);
        long h1 = toLong(h, 0);
        long h2 = toLong(h, 8) | 1L;
        long[] out = new long[hashFunctions];
        for (int i = 0; i < hashFunctions; i++) {
            out[i] = Math.floorMod(h1 + i * h2, bitCount);
        }
        return out;
    }

    private static long toLong(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[off + i] & 0xFF);
        }
        return v;
    }

    public int prefixBytes() {
        return prefixBytes;
    }

    public int hashFunctions() {
        return hashFunctions;
    }

    public long bitCount() {
        return bitCount;
    }

    public synchronized long entries() {
        return entries;
    }
}
