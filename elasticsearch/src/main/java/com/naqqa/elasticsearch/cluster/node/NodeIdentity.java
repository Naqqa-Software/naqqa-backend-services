package com.naqqa.elasticsearch.cluster.node;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Base64;

public final class NodeIdentity {

    private NodeIdentity() {
    }

    public static String loadOrCreate(Path dataDir) throws IOException {
        Path idFile = dataDir.resolve("node.id");
        if (Files.exists(idFile)) {
            String id = Files.readString(idFile, StandardCharsets.UTF_8).trim();
            if (!id.isEmpty()) {
                return id;
            }
        }
        Files.createDirectories(dataDir);
        String id = generate();
        Path tmp = dataDir.resolve("node.id.tmp");
        Files.writeString(tmp, id, StandardCharsets.UTF_8);
        Files.move(tmp, idFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return id;
    }

    public static String generate() {
        byte[] bytes = new byte[15];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
