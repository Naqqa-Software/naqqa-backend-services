package com.naqqa.elasticsearch.transport;

import com.naqqa.elasticsearch.common.threadpool.ThreadPool;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class HandshakeTest {

    @Test
    public void testGarbageMagicBytesAreRejected() throws Exception {
        ThreadPool pool = new ThreadPool();
        TransportService service = new TransportService("server", new InetSocketAddress("127.0.0.1", 0), pool);
        service.start();
        try {
            InetSocketAddress addr = service.boundAddress();
            try (Socket socket = new Socket(addr.getAddress(), addr.getPort())) {
                socket.setSoTimeout(3000);
                socket.getOutputStream().write(new byte[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 });
                socket.getOutputStream().flush();
                int result = socket.getInputStream().read();
                Assert.assertEquals(-1, result);
            }
        } finally {
            service.close();
            pool.close();
        }
    }

    @Test
    public void testHandshakeVersionMismatchIsRejected() throws Exception {
        ThreadPool pool = new ThreadPool();
        TransportService service = new TransportService("server", new InetSocketAddress("127.0.0.1", 0), pool);
        service.start();
        try {
            InetSocketAddress addr = service.boundAddress();
            try (Socket socket = new Socket(addr.getAddress(), addr.getPort())) {
                socket.setSoTimeout(5000);
                long requestId = 42L;
                byte status = (byte) (TransportStatusMirror.REQUEST | TransportStatusMirror.HANDSHAKE);
                byte[] payload = intToBytes(9999);
                byte[] frame = buildFrame(requestId, status, TransportVersion.CURRENT, TransportService.HANDSHAKE_ACTION_NAME, payload);
                socket.getOutputStream().write(frame);
                socket.getOutputStream().flush();

                DecodedFrame response = readFrame(socket.getInputStream());
                Assert.assertEquals(requestId, response.requestId);
                Assert.assertTrue((response.status & TransportStatusMirror.ERROR) != 0, "expected ERROR status flag on rejected handshake response");
                Assert.assertEquals(TransportService.HANDSHAKE_ACTION_NAME, response.action);
                Assert.assertTrue(response.exceptionMessage.contains("incompatible") || response.exceptionMessage.contains("handshake"),
                    "unexpected rejection message: " + response.exceptionMessage);
            }
        } finally {
            service.close();
            pool.close();
        }
    }

    private static final class TransportStatusMirror {
        static final byte REQUEST = 1 << 0;
        static final byte ERROR = 1 << 1;
        static final byte HANDSHAKE = 1 << 3;
    }

    private static byte[] intToBytes(int v) {
        return new byte[] { (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v };
    }

    private static void writeVInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static byte[] buildFrame(long requestId, byte status, int version, String action, byte[] payload) throws IOException {
        byte[] actionBytes = action.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (int i = 7; i >= 0; i--) {
            body.write((int) (requestId >>> (i * 8)) & 0xFF);
        }
        body.write(status);
        body.write(intToBytes(version));
        writeVInt(body, actionBytes.length);
        body.write(actionBytes);
        body.write(payload);
        byte[] bodyBytes = body.toByteArray();

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write('E');
        frame.write('S');
        frame.write(intToBytes(bodyBytes.length));
        frame.write(bodyBytes);
        return frame.toByteArray();
    }

    private static final class DecodedFrame {
        long requestId;
        byte status;
        int version;
        String action;
        String exceptionMessage;
    }

    private static int readVInt(InputStream in) throws IOException {
        int b = in.read();
        int i = b & 0x7F;
        int shift = 7;
        while ((b & 0x80) != 0) {
            b = in.read();
            i |= (b & 0x7F) << shift;
            shift += 7;
        }
        return i;
    }

    private static byte[] readFully(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        int read = 0;
        while (read < len) {
            int n = in.read(buf, read, len - read);
            if (n < 0) {
                throw new IOException("unexpected end of stream");
            }
            read += n;
        }
        return buf;
    }

    private static DecodedFrame readFrame(InputStream in) throws IOException {
        byte[] magic = readFully(in, 2);
        if (magic[0] != 'E' || magic[1] != 'S') {
            throw new IOException("bad magic in test response");
        }
        int length = bytesToInt(readFully(in, 4));
        byte[] body = readFully(in, length);
        ByteArrayInputStream bin = new ByteArrayInputStream(body);
        DecodedFrame frame = new DecodedFrame();
        long requestId = 0;
        for (int i = 0; i < 8; i++) {
            requestId = (requestId << 8) | (bin.read() & 0xFF);
        }
        frame.requestId = requestId;
        frame.status = (byte) bin.read();
        frame.version = bytesToInt(readFully(bin, 4));
        int actionLen = readVInt(bin);
        byte[] actionBytes = readFully(bin, actionLen);
        frame.action = new String(actionBytes, StandardCharsets.UTF_8);
        byte[] remainder = bin.readAllBytes();
        if ((frame.status & TransportStatusMirror.ERROR) != 0) {
            ByteArrayInputStream pin = new ByteArrayInputStream(remainder);
            int classNameLen = readVInt(pin);
            readFully(pin, classNameLen);
            int messageLen = readVInt(pin);
            byte[] messageBytes = readFully(pin, messageLen);
            frame.exceptionMessage = new String(messageBytes, StandardCharsets.UTF_8);
        } else {
            frame.exceptionMessage = "";
        }
        return frame;
    }

    private static int bytesToInt(byte[] b) {
        return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
    }
}
