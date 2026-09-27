package com.naqqa.elasticsearch.index.seqno;

public final class LocalCheckpointTracker {

    private final Object mutex = new Object();
    private long[] bits;
    private long checkpoint;
    private long maxSeqNo;

    public LocalCheckpointTracker() {
        this(SequenceNumbers.NO_OPS_PERFORMED, SequenceNumbers.NO_OPS_PERFORMED);
    }

    public LocalCheckpointTracker(long checkpoint, long maxSeqNo) {
        this.checkpoint = checkpoint;
        this.maxSeqNo = maxSeqNo;
        this.bits = new long[16];
    }

    public long generateSeqNo() {
        synchronized (mutex) {
            return ++maxSeqNo;
        }
    }

    public void markSeqNoAsProcessed(long seqNo) {
        synchronized (mutex) {
            if (seqNo < 0) {
                throw new IllegalArgumentException("invalid seq_no [" + seqNo + "]");
            }
            if (seqNo > maxSeqNo) {
                maxSeqNo = seqNo;
            }
            if (seqNo <= checkpoint) {
                return;
            }
            ensureCapacity(seqNo);
            setBit(seqNo);
            if (seqNo == checkpoint + 1) {
                advanceCheckpoint();
            }
        }
    }

    public boolean hasProcessed(long seqNo) {
        synchronized (mutex) {
            if (seqNo < 0) {
                return false;
            }
            if (seqNo <= checkpoint) {
                return true;
            }
            if (seqNo > maxSeqNo) {
                return false;
            }
            return getBit(seqNo);
        }
    }

    public long getCheckpoint() {
        synchronized (mutex) {
            return checkpoint;
        }
    }

    public long getMaxSeqNo() {
        synchronized (mutex) {
            return maxSeqNo;
        }
    }

    public long getProcessedCount() {
        synchronized (mutex) {
            long count = checkpoint + 1;
            for (int i = 0; i < bits.length * 64; i++) {
                long seqNo = checkpoint + 1 + i;
                if (seqNo > maxSeqNo) {
                    break;
                }
                if (getBit(seqNo)) {
                    count++;
                }
            }
            return count;
        }
    }

    private void advanceCheckpoint() {
        long next = checkpoint + 1;
        while (next <= maxSeqNo && getBit(next)) {
            clearBit(next);
            checkpoint = next;
            next++;
        }
    }

    private void ensureCapacity(long seqNo) {
        int requiredWord = (int) (seqNo >>> 6) + 1;
        if (requiredWord > bits.length) {
            int newLength = Math.max(bits.length * 2, requiredWord);
            long[] grown = new long[newLength];
            System.arraycopy(bits, 0, grown, 0, bits.length);
            bits = grown;
        }
    }

    private void setBit(long seqNo) {
        int word = (int) (seqNo >>> 6);
        int bit = (int) (seqNo & 0x3F);
        bits[word] |= (1L << bit);
    }

    private void clearBit(long seqNo) {
        int word = (int) (seqNo >>> 6);
        int bit = (int) (seqNo & 0x3F);
        bits[word] &= ~(1L << bit);
    }

    private boolean getBit(long seqNo) {
        int word = (int) (seqNo >>> 6);
        if (word >= bits.length) {
            return false;
        }
        int bit = (int) (seqNo & 0x3F);
        return (bits[word] & (1L << bit)) != 0;
    }
}
