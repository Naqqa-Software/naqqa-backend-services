package com.naqqa.elasticsearch.store;

import com.naqqa.elasticsearch.test.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertThrows;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class StoreFoundationTest {

    @Test
    public void fsDirectoryRoundTripsVIntsAndStringsWithChecksum() throws Exception {
        Path dir = Files.createTempDirectory("naqqa-store-it");
        try (FSDirectory directory = new FSDirectory(dir)) {
            try (IndexOutput out = directory.createOutput("f", IOContext.DEFAULT)) {
                CodecUtil.writeHeader(out, "TestCodec", 1);
                out.writeVInt(300);
                out.writeVLong(Long.MAX_VALUE - 5);
                out.writeZInt(-42);
                out.writeString("hello world");
                CodecUtil.writeFooter(out);
            }
            try (IndexInput in = directory.openInput("f", IOContext.DEFAULT)) {
                CodecUtil.checkHeader(in, "TestCodec", 1, 1);
                assertEquals(300, in.readVInt());
                assertEquals(Long.MAX_VALUE - 5, in.readVLong());
                assertEquals(-42, in.readZInt());
                assertEquals("hello world", in.readString());
            }
            assertEquals(CodecUtil.checksumEntireFile(directory.openInput("f", IOContext.DEFAULT)),
                CodecUtil.checksumEntireFile(directory.openInput("f", IOContext.DEFAULT)));
        }
    }

    @Test
    public void corruptByteFailsChecksumVerification() throws Exception {
        Path dir = Files.createTempDirectory("naqqa-store-it2");
        try (FSDirectory directory = new FSDirectory(dir)) {
            try (IndexOutput out = directory.createOutput("g", IOContext.DEFAULT)) {
                CodecUtil.writeHeader(out, "TestCodec", 1);
                for (int i = 0; i < 100; i++) {
                    out.writeVInt(i * 7);
                }
                CodecUtil.writeFooter(out);
            }
            byte[] bytes = Files.readAllBytes(dir.resolve("g"));
            bytes[bytes.length / 2] ^= 0x33;
            Files.write(dir.resolve("g"), bytes);
            assertThrows(CorruptIndexException.class, () -> {
                try (IndexInput in = directory.openInput("g", IOContext.DEFAULT)) {
                    CodecUtil.checksumEntireFile(in);
                }
            });
        }
    }

    @Test
    public void lockPreventsSecondWriter() throws Exception {
        Path dir = Files.createTempDirectory("naqqa-store-it3");
        try (FSDirectory directory = new FSDirectory(dir)) {
            Lock first = directory.obtainLock("write.lock");
            assertThrows(LockObtainFailedException.class, () -> directory.obtainLock("write.lock"));
            first.close();
            Lock second = directory.obtainLock("write.lock");
            second.close();
        }
    }

    @Test
    public void atomicRenameMakesFileVisibleUnderNewName() throws Exception {
        Path dir = Files.createTempDirectory("naqqa-store-it4");
        try (FSDirectory directory = new FSDirectory(dir)) {
            try (IndexOutput out = directory.createOutput("pending_x", IOContext.DEFAULT)) {
                out.writeInt(12345);
            }
            directory.rename("pending_x", "final_x");
            directory.syncMetaData();
            assertTrue(directory.fileExists("final_x"));
            assertTrue(!directory.fileExists("pending_x"));
        }
    }

    @Test
    public void byteBuffersAndMMapDirectoriesRoundTripRandomData() throws Exception {
        Random random = new Random(5);
        byte[] data = new byte[10000];
        random.nextBytes(data);

        ByteBuffersDirectory bbDir = new ByteBuffersDirectory();
        try (IndexOutput out = bbDir.createOutput("d", IOContext.DEFAULT)) {
            out.writeBytes(data, 0, data.length);
        }
        try (IndexInput in = bbDir.openInput("d", IOContext.DEFAULT)) {
            byte[] read = new byte[data.length];
            in.readBytes(read, 0, read.length);
            assertTrue(java.util.Arrays.equals(data, read));
        }

        Path dir = Files.createTempDirectory("naqqa-store-it5");
        try (MMapDirectory mmapDir = new MMapDirectory(dir)) {
            try (IndexOutput out = mmapDir.createOutput("m", IOContext.DEFAULT)) {
                out.writeBytes(data, 0, data.length);
            }
            try (IndexInput in = mmapDir.openInput("m", IOContext.DEFAULT)) {
                byte[] read = new byte[data.length];
                in.readBytes(read, 0, read.length);
                assertTrue(java.util.Arrays.equals(data, read));
            }
        }
    }
}
