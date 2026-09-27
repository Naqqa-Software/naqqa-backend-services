package com.naqqa.elasticsearch.common;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

public final class UUIDs {

    private UUIDs() {
    }

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final byte[] SECURE_MUNGED_ADDRESS = MacAddressProvider.getSecureMungedAddress();
    private static final AtomicLong SEQUENCE = new AtomicLong(SECURE_RANDOM.nextInt());
    private static final int SEQUENCE_BITS = 15;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    public static String randomBase64UUID() {
        return randomBase64UUID(SECURE_RANDOM);
    }

    public static String randomBase64UUID(Random random) {
        byte[] randomBytes = new byte[16];
        random.nextBytes(randomBytes);
        randomBytes[6] &= 0x0f;
        randomBytes[6] |= 0x40;
        randomBytes[8] &= 0x3f;
        randomBytes[8] |= 0x80;
        return ENCODER.encodeToString(randomBytes);
    }

    public static String base64TimeBasedUUID() {
        long timestamp = System.currentTimeMillis();
        long sequenceId = SEQUENCE.incrementAndGet() & ((1L << SEQUENCE_BITS) - 1);

        byte[] uuidBytes = new byte[15];
        uuidBytes[0] = (byte) (sequenceId >> 8);
        uuidBytes[1] = (byte) (sequenceId);

        uuidBytes[2] = (byte) (timestamp >> 40);
        uuidBytes[3] = (byte) (timestamp >> 32);
        uuidBytes[4] = (byte) (timestamp >> 24);
        uuidBytes[5] = (byte) (timestamp >> 16);
        uuidBytes[6] = (byte) (timestamp >> 8);
        uuidBytes[7] = (byte) (timestamp);

        System.arraycopy(SECURE_MUNGED_ADDRESS, 0, uuidBytes, 8, 6);

        int extraEntropy = SECURE_RANDOM.nextInt();
        uuidBytes[13] = (byte) (extraEntropy >> 8);
        uuidBytes[14] = (byte) (extraEntropy);

        return ENCODER.encodeToString(uuidBytes);
    }

    static final class MacAddressProvider {
        private MacAddressProvider() {
        }

        static byte[] getSecureMungedAddress() {
            byte[] address = getMacAddress();
            if (address == null || address.length == 0) {
                address = new byte[6];
                SECURE_RANDOM.nextBytes(address);
            }
            byte[] munged = new byte[6];
            SECURE_RANDOM.nextBytes(munged);
            for (int i = 0; i < 6 && i < address.length; i++) {
                munged[i] ^= address[i];
            }
            return munged;
        }

        static byte[] getMacAddress() {
            try {
                java.util.Enumeration<java.net.NetworkInterface> ifs = java.net.NetworkInterface.getNetworkInterfaces();
                if (ifs != null) {
                    while (ifs.hasMoreElements()) {
                        java.net.NetworkInterface ni = ifs.nextElement();
                        byte[] addr = ni.getHardwareAddress();
                        if (addr != null && addr.length == 6) {
                            return addr;
                        }
                    }
                }
            } catch (Exception e) {
                return null;
            }
            return null;
        }
    }
}
