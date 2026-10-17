package com.naqqa.auth.support;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.naqqa.sso.crypto.Pem;
import com.naqqa.sso.util.Bytes;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public class TestEnvInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    public static final String MONGO = "mongodb://127.0.0.1:27017";
    public static final Path ENV_FILE = Path.of("/home/alex/naqqa/sso-e2e/.env");

    static final List<String> DATABASES = new CopyOnWriteArrayList<>();
    static final String PEPPER = Bytes.b64url(Bytes.random(32));
    static volatile Path keyDir;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (DATABASES.isEmpty()) {
                return;
            }
            try (MongoClient client = MongoClients.create(MONGO + "/?serverSelectionTimeoutMS=1500")) {
                for (String db : DATABASES) {
                    client.getDatabase(db).drop();
                }
            } catch (RuntimeException ignored) {
            }
        }));
    }

    static synchronized Path keys() {
        if (keyDir != null) {
            return keyDir;
        }
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            Path dir = Files.createTempDirectory("naqqa-auth-keys");
            Files.writeString(dir.resolve("public.pem"), Pem.encodePublicKey(kp.getPublic()), StandardCharsets.US_ASCII);
            Files.writeString(dir.resolve("private.pem"), Pem.encodePrivateKey(kp.getPrivate()), StandardCharsets.US_ASCII);
            dir.toFile().deleteOnExit();
            dir.resolve("public.pem").toFile().deleteOnExit();
            dir.resolve("private.pem").toFile().deleteOnExit();
            keyDir = dir;
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String redisPassword() {
        String env = System.getenv("REDIS_PASSWORD");
        if (env != null && !env.isBlank()) {
            return env;
        }
        try {
            if (Files.isReadable(ENV_FILE)) {
                for (String line : Files.readAllLines(ENV_FILE)) {
                    if (line.startsWith("REDIS_PASSWORD=")) {
                        return line.substring("REDIS_PASSWORD=".length()).trim().replace("\"", "");
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return "";
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        Path dir = keys();
        String db = "naqqa_auth_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        DATABASES.add(db);
        Map<String, Object> props = new HashMap<>();
        props.put("spring.data.mongodb.uri", MONGO + "/" + db);
        props.put("rsa.public-key", dir.resolve("public.pem").toUri().toString());
        props.put("rsa.private-key", dir.resolve("private.pem").toUri().toString());
        props.put("spring.data.redis.password", redisPassword());
        props.put("NAQQA_SSO_PEPPER", PEPPER);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("naqqa-auth-test", props));
    }

    public static String publicKeyPath() {
        return keys().resolve("public.pem").toString();
    }
}
